use common::bytesrepr::ToBytes;
use history::trie::{self, Parents, Pointer, Trie};
use history::trie_store::{Readable, TrieStore, Writable};
use shared::newtypes::Blake2bHash;

#[cfg(test)]
mod tests;

#[derive(Debug, PartialEq, Eq)]
pub enum ReadResult<V> {
    Found(V),
    NotFound,
    RootNotFound,
}

/// Returns a value from the corresponding key at a given root in a given store
pub fn read<K, V, T, S, E>(
    txn: &T,
    store: &S,
    root: &Blake2bHash,
    key: &K,
) -> Result<ReadResult<V>, E>
where
    K: ToBytes + Eq + std::fmt::Debug,
    V: ToBytes,
    T: Readable<Handle = S::Handle>,
    S: TrieStore<K, V>,
    S::Error: From<T::Error>,
    E: From<S::Error> + From<common::bytesrepr::Error>,
{
    let path: Vec<u8> = key.to_bytes()?;

    let mut depth: usize = 0;
    let mut current: Trie<K, V> = match store.get(txn, root)? {
        Some(root) => root,
        None => return Ok(ReadResult::RootNotFound),
    };

    loop {
        match current {
            Trie::Leaf {
                key: leaf_key,
                value: leaf_value,
            } => {
                let result = if *key == leaf_key {
                    ReadResult::Found(leaf_value)
                } else {
                    // Keys may not match in the case of a compressed path from
                    // a Node directly to a Leaf
                    ReadResult::NotFound
                };
                return Ok(result);
            }
            Trie::Node { pointer_block } => {
                let index: usize = {
                    assert!(depth < path.len(), "depth must be < {}", path.len());
                    path[depth].into()
                };
                let maybe_pointer: Option<Pointer> = {
                    assert!(index < trie::RADIX, "key length must be < {}", trie::RADIX);
                    pointer_block[index]
                };
                match maybe_pointer {
                    Some(pointer) => match store.get(txn, pointer.hash())? {
                        Some(next) => {
                            depth += 1;
                            current = next;
                        }
                        None => {
                            panic!(
                                "No trie value at key: {:?} (reading from key: {:?})",
                                pointer.hash(),
                                key
                            );
                        }
                    },
                    None => {
                        return Ok(ReadResult::NotFound);
                    }
                }
            }
            Trie::Extension { affix, pointer } => {
                let sub_path = &path[depth..depth + affix.len()];
                if sub_path == affix.as_slice() {
                    match store.get(txn, pointer.hash())? {
                        Some(next) => {
                            depth += affix.len();
                            current = next;
                        }
                        None => {
                            panic!(
                                "No trie value at key: {:?} (reading from key: {:?})",
                                pointer.hash(),
                                key
                            );
                        }
                    }
                } else {
                    return Ok(ReadResult::NotFound);
                }
            }
        }
    }
}

struct TrieScan<K, V> {
    tip: Trie<K, V>,
    parents: Parents<K, V>,
}

impl<K, V> TrieScan<K, V> {
    fn new(tip: Trie<K, V>, parents: Parents<K, V>) -> Self {
        TrieScan { tip, parents }
    }
}

/// Returns a [`TrieScan`] from the given key at a given root in a given store.
/// A scan consists of the deepest trie variant found at that key, a.k.a. the
/// "tip", along the with the parents of that variant. Parents are ordered by
/// their depth from the root (shallow to deep).
fn scan<K, V, T, S, E>(
    txn: &T,
    store: &S,
    key_bytes: &[u8],
    root: &Trie<K, V>,
) -> Result<TrieScan<K, V>, E>
where
    K: ToBytes + Clone,
    V: ToBytes + Clone,
    T: Readable<Handle = S::Handle>,
    S: TrieStore<K, V>,
    S::Error: From<T::Error>,
    E: From<S::Error> + From<common::bytesrepr::Error>,
{
    let path = key_bytes;

    let mut current = root.to_owned();
    let mut depth: usize = 0;
    let mut acc: Parents<K, V> = Vec::new();

    loop {
        match current {
            leaf @ Trie::Leaf { .. } => {
                return Ok(TrieScan::new(leaf, acc));
            }
            Trie::Node { pointer_block } => {
                let index: usize = {
                    assert!(depth < path.len(), "depth must be < {}", path.len());
                    path[depth].into()
                };
                let maybe_pointer: Option<Pointer> = {
                    assert!(index < trie::RADIX, "index must be < {}", trie::RADIX);
                    pointer_block[index]
                };
                let pointer = match maybe_pointer {
                    Some(pointer) => pointer,
                    None => return Ok(TrieScan::new(Trie::Node { pointer_block }, acc)),
                };
                match store.get(txn, pointer.hash())? {
                    Some(next) => {
                        current = next;
                        depth += 1;
                        acc.push((index, Trie::Node { pointer_block }))
                    }
                    None => {
                        panic!(
                            "No trie value at key: {:?} (reading from path: {:?})",
                            pointer.hash(),
                            path
                        );
                    }
                }
            }
            Trie::Extension { affix, pointer } => {
                let sub_path = &path[depth..depth + affix.len()];
                if sub_path != affix.as_slice() {
                    return Ok(TrieScan::new(Trie::Extension { affix, pointer }, acc));
                }
                match store.get(txn, pointer.hash())? {
                    Some(next) => {
                        let index: usize = {
                            assert!(depth < path.len(), "depth must be < {}", path.len());
                            path[depth].into()
                        };
                        current = next;
                        depth += affix.len();
                        acc.push((index, Trie::Extension { affix, pointer }))
                    }
                    None => {
                        panic!(
                            "No trie value at key: {:?} (reading from path: {:?})",
                            pointer.hash(),
                            path
                        );
                    }
                }
            }
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum WriteResult {
    Written(Blake2bHash),
    AlreadyExists,
    RootNotFound,
}

pub fn write<K, V, T, S, E>(
    txn: &mut T,
    store: &S,
    root: &Blake2bHash,
    key: &K,
    value: &V,
) -> Result<WriteResult, E>
where
    K: ToBytes + Clone + Eq + std::fmt::Debug,
    V: ToBytes + Clone + Eq,
    T: Readable<Handle = S::Handle> + Writable<Handle = S::Handle>,
    S: TrieStore<K, V>,
    S::Error: From<T::Error>,
    E: From<S::Error> + From<common::bytesrepr::Error>,
{
    match store.get(txn, root)? {
        None => Ok(WriteResult::RootNotFound),
        Some(current_root) => {
            let new_leaf = Trie::Leaf {
                key: key.to_owned(),
                value: value.to_owned(),
            };
            let path: Vec<u8> = key.to_bytes()?;
            let TrieScan { tip, parents: _ } =
                scan::<K, V, T, S, E>(txn, store, &path, &current_root)?;
            let new_elements: Vec<(Blake2bHash, Trie<K, V>)> = match tip {
                // If the "tip" is the same as the new leaf, then the leaf
                // is already in the Trie.
                Trie::Leaf { .. } if new_leaf == tip => Vec::new(),
                // If the "tip" is an existing leaf with the same key as the
                // new leaf, but the existing leaf and new leaf have different
                // values, then we are in the situation where we are "updating"
                // an existing leaf.
                Trie::Leaf {
                    key: ref leaf_key,
                    value: ref leaf_value,
                } if key == leaf_key && value != leaf_value => unimplemented!(),
                // If the "tip" is an existing leaf with a different key than
                // the new leaf, then we are in a situation where the new leaf
                // shares some common prefix with the existing leaf.
                Trie::Leaf {
                    key: ref leaf_key, ..
                } if key != leaf_key => unimplemented!(),
                // This case is unreachable, but the compiler can't figure
                // that out.
                Trie::Leaf { .. } => unreachable!(),
                // If the "tip" is an existing node, then we can add the new
                // leaf's hash to the node's pointer block and rehash.
                Trie::Node { .. } => unimplemented!(),
                // If the "tip" is an extension node, then we must modify or
                // replace it, adding a node where necessary.
                Trie::Extension { .. } => unimplemented!(),
            };
            if new_elements.is_empty() {
                return Ok(WriteResult::AlreadyExists);
            }
            let mut root_hash = root.to_owned();
            for (hash, element) in new_elements.iter() {
                store.put(txn, hash, element)?;
                root_hash = *hash;
            }
            Ok(WriteResult::Written(root_hash))
        }
    }
}
