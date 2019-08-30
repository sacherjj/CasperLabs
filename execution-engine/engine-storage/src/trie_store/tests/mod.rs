mod concurrent;
mod proptests;
mod simple;

use contract_ffi::bytesrepr::ToBytes;
use engine_shared::newtypes::Blake2bHash;

use crate::transaction_source::{Readable, Writable};
use crate::trie::Trie;
use crate::trie::{Pointer, PointerBlock};
use crate::trie_store::TrieStore;

#[derive(Clone)]
struct TestData<K, V>(Blake2bHash, Trie<K, V>);

fn create_data() -> Vec<TestData<Vec<u8>, Vec<u8>>> {
    let leaf_1 = Trie::Leaf {
        key: vec![0u8, 0, 0],
        value: b"val_1".to_vec(),
    };
    let leaf_2 = Trie::Leaf {
        key: vec![1u8, 0, 0],
        value: b"val_2".to_vec(),
    };
    let leaf_3 = Trie::Leaf {
        key: vec![1u8, 0, 1],
        value: b"val_3".to_vec(),
    };

    let leaf_1_hash = Blake2bHash::new(&leaf_1.to_bytes().unwrap());
    let leaf_2_hash = Blake2bHash::new(&leaf_2.to_bytes().unwrap());
    let leaf_3_hash = Blake2bHash::new(&leaf_3.to_bytes().unwrap());

    let node_2: Trie<Vec<u8>, Vec<u8>> = {
        let mut pointer_block = PointerBlock::new();
        pointer_block[0] = Some(Pointer::LeafPointer(leaf_2_hash));
        pointer_block[1] = Some(Pointer::LeafPointer(leaf_3_hash));
        let pointer_block = Box::new(pointer_block);
        Trie::Node { pointer_block }
    };

    let node_2_hash = Blake2bHash::new(&node_2.to_bytes().unwrap());

    let ext_node: Trie<Vec<u8>, Vec<u8>> = {
        let affix = vec![1u8, 0];
        let pointer = Pointer::NodePointer(node_2_hash);
        Trie::Extension { affix, pointer }
    };

    let ext_node_hash = Blake2bHash::new(&ext_node.to_bytes().unwrap());

    let node_1: Trie<Vec<u8>, Vec<u8>> = {
        let mut pointer_block = PointerBlock::new();
        pointer_block[0] = Some(Pointer::LeafPointer(leaf_1_hash));
        pointer_block[1] = Some(Pointer::NodePointer(ext_node_hash));
        let pointer_block = Box::new(pointer_block);
        Trie::Node { pointer_block }
    };

    let node_1_hash = Blake2bHash::new(&node_1.to_bytes().unwrap());

    vec![
        TestData(leaf_1_hash, leaf_1),
        TestData(leaf_2_hash, leaf_2),
        TestData(leaf_3_hash, leaf_3),
        TestData(node_1_hash, node_1),
        TestData(node_2_hash, node_2),
        TestData(ext_node_hash, ext_node),
    ]
}

fn put_many<K, V, T, S, E>(txn: &mut T, store: &S, items: &[TestData<K, V>]) -> Result<(), E>
where
    K: ToBytes,
    V: ToBytes,
    T: Writable<Handle = S::Handle>,
    S: TrieStore<K, V>,
    S::Error: From<T::Error>,
    E: From<S::Error>,
{
    for TestData(hash, leaf) in items.iter() {
        store.put::<T>(txn, hash, leaf)?;
    }
    Ok(())
}

fn get_many<K, V, T, S, E>(
    txn: &T,
    store: &S,
    keys: &[&Blake2bHash],
) -> Result<Vec<Option<Trie<K, V>>>, E>
where
    K: ToBytes,
    V: ToBytes,
    T: Readable<Handle = S::Handle>,
    S: TrieStore<K, V>,
    S::Error: From<T::Error>,
    E: From<S::Error>,
{
    let mut ret = Vec::new();

    for hash in keys.iter() {
        let maybe_value = store.get::<T>(txn, hash)?;
        ret.push(maybe_value);
    }
    Ok(ret)
}
