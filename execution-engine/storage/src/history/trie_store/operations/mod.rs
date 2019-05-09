use common::bytesrepr::{self, ToBytes};
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
                let index = {
                    assert!(depth < path.len(), "depth must be < {}", path.len());
                    path[depth]
                };
                let maybe_pointer: Option<Pointer> = {
                    let index: usize = index.into();
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
                        let index = {
                            assert!(depth < path.len(), "depth must be < {}", path.len());
                            path[depth]
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

#[allow(clippy::type_complexity)]
fn rehash<K, V>(
    mut tip: Trie<K, V>,
    parents: Parents<K, V>,
) -> Result<Vec<(Blake2bHash, Trie<K, V>)>, bytesrepr::Error>
where
    K: ToBytes + Clone,
    V: ToBytes + Clone,
{
    let mut ret: Vec<(Blake2bHash, Trie<K, V>)> = Vec::new();
    let mut tip_hash = {
        let trie_bytes = tip.to_bytes()?;
        Blake2bHash::new(&trie_bytes)
    };
    ret.push((tip_hash, tip.to_owned()));

    for (index, parent) in parents.into_iter().rev() {
        match parent {
            Trie::Leaf { .. } => {
                panic!("parents should not contain any leaves");
            }
            Trie::Node { mut pointer_block } => {
                tip = {
                    let pointer = match tip {
                        Trie::Leaf { .. } => Pointer::LeafPointer(tip_hash),
                        Trie::Node { .. } => Pointer::NodePointer(tip_hash),
                        Trie::Extension { .. } => Pointer::NodePointer(tip_hash),
                    };
                    pointer_block[index.into()] = Some(pointer);
                    Trie::Node { pointer_block }
                };
                tip_hash = {
                    let node_bytes = tip.to_bytes()?;
                    Blake2bHash::new(&node_bytes)
                };
                ret.push((tip_hash, tip.to_owned()))
            }
            Trie::Extension { affix, pointer } => {
                tip = {
                    let pointer = pointer.update(tip_hash);
                    Trie::Extension { affix, pointer }
                };
                tip_hash = {
                    let extension_bytes = tip.to_bytes()?;
                    Blake2bHash::new(&extension_bytes)
                };
                ret.push((tip_hash, tip.to_owned()))
            }
        }
    }
    Ok(ret)
}

fn common_prefix<A: Eq + Clone>(ls: &[A], rs: &[A]) -> Vec<A> {
    ls.iter()
        .zip(rs.iter())
        .take_while(|(l, r)| l == r)
        .map(|(l, _)| l.to_owned())
        .collect()
}

fn get_parents_path<K, V>(parents: &[(u8, Trie<K, V>)]) -> Vec<u8> {
    let mut ret = Vec::new();
    for (index, element) in parents.iter() {
        if let Trie::Extension { affix, .. } = element {
            ret.extend(affix);
        } else {
            ret.push(index.to_owned());
        }
    }
    ret
}

/// Takes a path to a leaf, that leaf's parent node, and the parents of that
/// node, and adds the node to the parents.
///
/// This function will panic if the the path to the leaf and the path to its
/// parent node do not share a common prefix.
fn add_node_to_parents<K, V>(
    path_to_leaf: &[u8],
    new_parent_node: Trie<K, V>,
    mut parents: Parents<K, V>,
) -> Result<Parents<K, V>, bytesrepr::Error>
where
    K: ToBytes,
    V: ToBytes,
{
    // TODO: add is_node() method to Trie
    match new_parent_node {
        Trie::Node { .. } => (),
        _ => panic!("new_parent must be a node"),
    }
    // The current depth will be the length of the path to the new parent node.
    let depth: usize = {
        // Get the path to this node
        let path_to_node: Vec<u8> = get_parents_path(&parents);
        // Check that the path to the node is a prefix of the current path
        let current_path = common_prefix(&path_to_leaf, &path_to_node);
        assert_eq!(current_path, path_to_node);
        // Get the length
        path_to_node.len()
    };
    // Index path by current depth;
    let index = {
        assert!(
            depth < path_to_leaf.len(),
            "depth must be < {}",
            path_to_leaf.len()
        );
        path_to_leaf[depth]
    };
    // Add node to parents, along with index to modify
    parents.push((index, new_parent_node));
    Ok(parents)
}

/// Takes paths to a new leaf and an existing leaf that share a common prefix,
/// along with the parents of the existing leaf. Creates a new node (adding a
/// possible parent extension for it to parents) which contains the existing
/// leaf.  Returns the new node and parents, so that they can be used by
/// [`add_node_to_parents`].
#[allow(clippy::type_complexity)]
fn reparent_leaf<K, V>(
    new_leaf_path: &[u8],
    existing_leaf_path: &[u8],
    parents: Parents<K, V>,
) -> Result<(Trie<K, V>, Parents<K, V>), bytesrepr::Error>
where
    K: ToBytes,
    V: ToBytes,
{
    let mut parents = parents;
    let (child_index, parent) = parents.pop().expect("parents should not be empty");
    let pointer_block = match parent {
        Trie::Node { pointer_block } => pointer_block,
        _ => panic!("A leaf should have a node for its parent"),
    };
    // Get the path that the new leaf and existing leaf share
    let shared_path = common_prefix(&new_leaf_path, &existing_leaf_path);
    // Assemble a new node to hold the existing leaf. The new leaf will
    // be added later during the add_parent_node and rehash phase.
    let new_node = {
        let index: usize = existing_leaf_path[shared_path.len()].into();
        let existing_leaf_pointer =
            pointer_block[child_index.into()].expect("parent has lost the existing leaf");
        Trie::node(&[(index, existing_leaf_pointer)])
    };
    // Re-add the parent node to parents
    parents.push((child_index, Trie::Node { pointer_block }));
    // Create an affix for a possible extension node
    let affix = {
        let parents_path = get_parents_path(&parents);
        &shared_path[parents_path.len()..]
    };
    // If the affix is non-empty, create an extension node and add it
    // to parents.
    if !affix.is_empty() {
        let new_node_bytes = new_node.to_bytes()?;
        let new_node_hash = Blake2bHash::new(&new_node_bytes);
        let new_extension = Trie::extension(affix.to_vec(), Pointer::NodePointer(new_node_hash));
        parents.push((child_index, new_extension));
    }
    Ok((new_node, parents))
}

struct SplitResult<K, V> {
    new_node: Trie<K, V>,
    parents: Parents<K, V>,
    maybe_hashed_child_extension: Option<(Blake2bHash, Trie<K, V>)>,
}

/// Takes a path to a new leaf, an existing extension that leaf collides with,
/// and the parents of that extension.  Creates a new node and possible parent
/// and child extensions.  The node pointer contained in the existing extension
/// is repositioned in the new node or the possible child extension.  The
/// possible parent extension is added to parents.  Returns the new node,
/// parents, and the the possible child extension (paired with its hash).
/// The new node and parents can be used by [`add_node_to_parents`], and the
/// new hashed child extension can be added to the list of new trie elements.
fn split_extension<K, V>(
    new_leaf_path: &[u8],
    existing_extension: Trie<K, V>,
    mut parents: Parents<K, V>,
) -> Result<SplitResult<K, V>, bytesrepr::Error>
where
    K: ToBytes + Clone,
    V: ToBytes + Clone,
{
    // TODO: add is_extension() method to Trie
    let (affix, pointer) = match existing_extension {
        Trie::Extension { affix, pointer } => (affix, pointer),
        _ => panic!("existing_extension must be an extension"),
    };
    let parents_path = get_parents_path(&parents);
    // Get the path to the existing extension node
    let existing_extension_path: Vec<u8> =
        parents_path.iter().chain(affix.iter()).cloned().collect();
    // Get the path that the new leaf and existing leaf share
    let shared_path = common_prefix(&new_leaf_path, &existing_extension_path);
    // Create an affix for a possible parent extension above the new
    // node.
    let parent_extension_affix = shared_path[parents_path.len()..].to_vec();
    // Create an affix for a possible child extension between the new
    // node and the node that the existing extension pointed to.
    let child_extension_affix = affix[parent_extension_affix.len() + 1..].to_vec();
    // Create a child extension (paired with its hash) if necessary
    let maybe_hashed_child_extension: Option<(Blake2bHash, Trie<K, V>)> =
        if child_extension_affix.is_empty() {
            None
        } else {
            let child_extension = Trie::extension(child_extension_affix.to_vec(), pointer);
            let child_extension_bytes = child_extension.to_bytes()?;
            let child_extension_hash = Blake2bHash::new(&child_extension_bytes);
            Some((child_extension_hash, child_extension))
        };
    // Assemble a new node.
    let new_node: Trie<K, V> = {
        let index: usize = existing_extension_path[shared_path.len()].into();
        let pointer = maybe_hashed_child_extension
            .to_owned()
            .map_or_else(|| pointer, |(hash, _)| Pointer::NodePointer(hash));
        Trie::node(&[(index, pointer)])
    };
    // Create a parent extension if necessary
    if !parent_extension_affix.is_empty() {
        let new_node_bytes = new_node.to_bytes()?;
        let new_node_hash = Blake2bHash::new(&new_node_bytes);
        let parent_extension = Trie::extension(
            parent_extension_affix.to_vec(),
            Pointer::NodePointer(new_node_hash),
        );
        parents.push((parent_extension_affix[0], parent_extension));
    }
    Ok(SplitResult {
        new_node,
        parents,
        maybe_hashed_child_extension,
    })
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
            let TrieScan { tip, parents } =
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
                } if key == leaf_key && value != leaf_value => rehash(new_leaf, parents)?,
                // If the "tip" is an existing leaf with a different key than
                // the new leaf, then we are in a situation where the new leaf
                // shares some common prefix with the existing leaf.
                Trie::Leaf {
                    key: ref existing_leaf_key,
                    ..
                } if key != existing_leaf_key => {
                    let existing_leaf_path = existing_leaf_key.to_bytes()?;
                    let (new_node, parents) = reparent_leaf(&path, &existing_leaf_path, parents)?;
                    let parents = add_node_to_parents(&path, new_node, parents)?;
                    rehash(new_leaf, parents)?
                }
                // This case is unreachable, but the compiler can't figure
                // that out.
                Trie::Leaf { .. } => unreachable!(),
                // If the "tip" is an existing node, then we can add a pointer
                // to the new leaf to the node's pointer block.
                node @ Trie::Node { .. } => {
                    let parents = add_node_to_parents(&path, node, parents)?;
                    rehash(new_leaf, parents)?
                }
                // If the "tip" is an extension node, then we must modify or
                // replace it, adding a node where necessary.
                extension @ Trie::Extension { .. } => {
                    let SplitResult {
                        new_node,
                        parents,
                        maybe_hashed_child_extension,
                    } = split_extension(&path, extension, parents)?;
                    let parents = add_node_to_parents(&path, new_node, parents)?;
                    if let Some(hashed_extension) = maybe_hashed_child_extension {
                        let mut ret = vec![hashed_extension];
                        ret.extend(rehash(new_leaf, parents)?);
                        ret
                    } else {
                        rehash(new_leaf, parents)?
                    }
                }
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
