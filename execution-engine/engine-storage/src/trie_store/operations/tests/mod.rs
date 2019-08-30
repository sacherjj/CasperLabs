mod proptests;
mod read;
mod scan;
mod write;

use std::collections::HashMap;

use failure;
use lmdb::DatabaseFlags;
use tempfile::{tempdir, TempDir};

use contract_ffi::bytesrepr::{self, FromBytes, ToBytes};
use engine_shared::newtypes::{Blake2bHash, CorrelationId};

use crate::error::{self, in_memory};
use crate::transaction_source::in_memory::InMemoryEnvironment;
use crate::transaction_source::lmdb::LmdbEnvironment;
use crate::transaction_source::{Readable, Transaction, TransactionSource};
use crate::trie::{Pointer, Trie};
use crate::trie_store::in_memory::InMemoryTrieStore;
use crate::trie_store::lmdb::LmdbTrieStore;
use crate::trie_store::operations::{read, write, ReadResult, WriteResult};
use crate::trie_store::TrieStore;
use crate::TEST_MAP_SIZE;

const TEST_KEY_LENGTH: usize = 7;

/// A short key type for tests.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
struct TestKey([u8; TEST_KEY_LENGTH]);

impl ToBytes for TestKey {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        Ok(self.0.to_vec())
    }
}

impl FromBytes for TestKey {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (key, rem) = bytes.split_at(TEST_KEY_LENGTH);
        let mut ret = [0u8; TEST_KEY_LENGTH];
        ret.copy_from_slice(key);
        Ok((TestKey(ret), rem))
    }
}

const TEST_VAL_LENGTH: usize = 6;

/// A short value type for tests.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
struct TestValue([u8; TEST_VAL_LENGTH]);

impl ToBytes for TestValue {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        Ok(self.0.to_vec())
    }
}

impl FromBytes for TestValue {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (key, rem) = bytes.split_at(TEST_VAL_LENGTH);
        let mut ret = [0u8; TEST_VAL_LENGTH];
        ret.copy_from_slice(key);
        Ok((TestValue(ret), rem))
    }
}

type TestTrie = Trie<TestKey, TestValue>;

/// A pairing of a trie element and its hash.
#[derive(Debug, Clone, PartialEq, Eq)]
struct HashedTestTrie {
    hash: Blake2bHash,
    trie: TestTrie,
}

impl HashedTestTrie {
    pub fn new(trie: TestTrie) -> Result<Self, bytesrepr::Error> {
        let trie_bytes = trie.to_bytes()?;
        let hash = Blake2bHash::new(&trie_bytes);
        Ok(HashedTestTrie { hash, trie })
    }
}

const TEST_LEAVES_LENGTH: usize = 6;

/// Keys have been chosen deliberately and the `create_` functions below depend
/// on these exact definitions.  Values are arbitrary.
const TEST_LEAVES: [TestTrie; TEST_LEAVES_LENGTH] = [
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 0, 0]),
        value: TestValue(*b"value0"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 0, 1]),
        value: TestValue(*b"value1"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 2, 0, 0, 0]),
        value: TestValue(*b"value2"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 255, 0]),
        value: TestValue(*b"value3"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 1, 0, 0, 0, 0, 0]),
        value: TestValue(*b"value4"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 2, 0, 0, 0, 0]),
        value: TestValue(*b"value5"),
    },
];

const TEST_LEAVES_UPDATED: [TestTrie; TEST_LEAVES_LENGTH] = [
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueA"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 0, 1]),
        value: TestValue(*b"valueB"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 2, 0, 0, 0]),
        value: TestValue(*b"valueC"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 255, 0]),
        value: TestValue(*b"valueD"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 1, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueE"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 2, 0, 0, 0, 0]),
        value: TestValue(*b"valueF"),
    },
];

const TEST_LEAVES_NON_COLLIDING: [TestTrie; TEST_LEAVES_LENGTH] = [
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueA"),
    },
    Trie::Leaf {
        key: TestKey([1u8, 0, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueB"),
    },
    Trie::Leaf {
        key: TestKey([2u8, 0, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueC"),
    },
    Trie::Leaf {
        key: TestKey([3u8, 0, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueD"),
    },
    Trie::Leaf {
        key: TestKey([4u8, 0, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueE"),
    },
    Trie::Leaf {
        key: TestKey([5u8, 0, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueF"),
    },
];

const TEST_LEAVES_ADJACENTS: [TestTrie; TEST_LEAVES_LENGTH] = [
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 0, 2]),
        value: TestValue(*b"valueA"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 0, 3]),
        value: TestValue(*b"valueB"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 3, 0, 0, 0]),
        value: TestValue(*b"valueC"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0, 0, 1, 0]),
        value: TestValue(*b"valueD"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 2, 0, 0, 0, 0, 0]),
        value: TestValue(*b"valueE"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 3, 0, 0, 0, 0]),
        value: TestValue(*b"valueF"),
    },
];

type TestTrieGenerator = fn() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error>;

const TEST_TRIE_GENERATORS_LENGTH: usize = 7;

const TEST_TRIE_GENERATORS: [TestTrieGenerator; TEST_TRIE_GENERATORS_LENGTH] = [
    create_0_leaf_trie,
    create_1_leaf_trie,
    create_2_leaf_trie,
    create_3_leaf_trie,
    create_4_leaf_trie,
    create_5_leaf_trie,
    create_6_leaf_trie,
];

fn hash_test_tries(tries: &[TestTrie]) -> Result<Vec<HashedTestTrie>, bytesrepr::Error> {
    tries
        .iter()
        .map(|trie| HashedTestTrie::new(trie.to_owned()))
        .collect()
}

fn create_0_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let root = HashedTestTrie::new(Trie::node(&[]))?;

    let root_hash: Blake2bHash = root.hash;

    let parents: Vec<HashedTestTrie> = vec![root];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(parents);
        ret
    };

    Ok((root_hash, tries))
}

fn create_1_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES[..1])?;

    let root = HashedTestTrie::new(Trie::node(&[(0, Pointer::LeafPointer(leaves[0].hash))]))?;

    let root_hash: Blake2bHash = root.hash;

    let parents: Vec<HashedTestTrie> = vec![root];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(parents);
        ret
    };

    Ok((root_hash, tries))
}

fn create_2_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES[..2])?;

    let node = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (1, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let ext = HashedTestTrie::new(Trie::extension(
        vec![0u8, 0, 0, 0, 0],
        Pointer::NodePointer(node.hash),
    ))?;

    let root = HashedTestTrie::new(Trie::node(&[(0, Pointer::NodePointer(ext.hash))]))?;

    let root_hash = root.hash;

    let parents: Vec<HashedTestTrie> = vec![root, ext, node];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(parents);
        ret
    };

    Ok((root_hash, tries))
}

fn create_3_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES[..3])?;

    let node_1 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (1, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let ext_1 = HashedTestTrie::new(Trie::extension(
        vec![0u8, 0],
        Pointer::NodePointer(node_1.hash),
    ))?;

    let node_2 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(ext_1.hash)),
        (2, Pointer::LeafPointer(leaves[2].hash)),
    ]))?;

    let ext_2 = HashedTestTrie::new(Trie::extension(
        vec![0u8, 0],
        Pointer::NodePointer(node_2.hash),
    ))?;

    let root = HashedTestTrie::new(Trie::node(&[(0, Pointer::NodePointer(ext_2.hash))]))?;

    let root_hash = root.hash;

    let parents: Vec<HashedTestTrie> = vec![root, ext_2, node_2, ext_1, node_1];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(parents);
        ret
    };

    Ok((root_hash, tries))
}

fn create_4_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES[..4])?;

    let node_1 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (1, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let node_2 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(node_1.hash)),
        (255, Pointer::LeafPointer(leaves[3].hash)),
    ]))?;

    let ext_1 = HashedTestTrie::new(Trie::extension(
        vec![0u8],
        Pointer::NodePointer(node_2.hash),
    ))?;

    let node_3 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(ext_1.hash)),
        (2, Pointer::LeafPointer(leaves[2].hash)),
    ]))?;

    let ext_2 = HashedTestTrie::new(Trie::extension(
        vec![0u8, 0],
        Pointer::NodePointer(node_3.hash),
    ))?;

    let root = HashedTestTrie::new(Trie::node(&[(0, Pointer::NodePointer(ext_2.hash))]))?;

    let root_hash = root.hash;

    let parents: Vec<HashedTestTrie> = vec![root, ext_2, node_3, ext_1, node_2, node_1];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(parents);
        ret
    };

    Ok((root_hash, tries))
}

fn create_5_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES[..5])?;

    let node_1 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (1, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let node_2 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(node_1.hash)),
        (255, Pointer::LeafPointer(leaves[3].hash)),
    ]))?;

    let ext_1 = HashedTestTrie::new(Trie::extension(
        vec![0u8],
        Pointer::NodePointer(node_2.hash),
    ))?;

    let node_3 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(ext_1.hash)),
        (2, Pointer::LeafPointer(leaves[2].hash)),
    ]))?;

    let ext_2 = HashedTestTrie::new(Trie::extension(
        vec![0u8],
        Pointer::NodePointer(node_3.hash),
    ))?;

    let node_4 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(ext_2.hash)),
        (1, Pointer::LeafPointer(leaves[4].hash)),
    ]))?;

    let root = HashedTestTrie::new(Trie::node(&[(0, Pointer::NodePointer(node_4.hash))]))?;

    let root_hash = root.hash;

    let parents: Vec<HashedTestTrie> = vec![root, node_4, ext_2, node_3, ext_1, node_2, node_1];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(parents);
        ret
    };

    Ok((root_hash, tries))
}

fn create_6_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES)?;

    let node_1 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (1, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let node_2 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(node_1.hash)),
        (255, Pointer::LeafPointer(leaves[3].hash)),
    ]))?;

    let ext = HashedTestTrie::new(Trie::extension(
        vec![0u8],
        Pointer::NodePointer(node_2.hash),
    ))?;

    let node_3 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(ext.hash)),
        (2, Pointer::LeafPointer(leaves[2].hash)),
    ]))?;

    let node_4 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(node_3.hash)),
        (2, Pointer::LeafPointer(leaves[5].hash)),
    ]))?;

    let node_5 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(node_4.hash)),
        (1, Pointer::LeafPointer(leaves[4].hash)),
    ]))?;

    let root = HashedTestTrie::new(Trie::node(&[(0, Pointer::NodePointer(node_5.hash))]))?;

    let root_hash = root.hash;

    let parents: Vec<HashedTestTrie> = vec![root, node_5, node_4, node_3, ext, node_2, node_1];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(parents);
        ret
    };

    Ok((root_hash, tries))
}

fn put_tries<'a, R, S, E>(environment: &'a R, store: &S, tries: &[HashedTestTrie]) -> Result<(), E>
where
    R: TransactionSource<'a, Handle = S::Handle>,
    S: TrieStore<TestKey, TestValue>,
    S::Error: From<R::Error>,
    E: From<R::Error> + From<S::Error> + From<contract_ffi::bytesrepr::Error>,
{
    if tries.is_empty() {
        return Ok(());
    }
    let mut txn = environment.create_read_write_txn()?;
    for HashedTestTrie { hash, trie } in tries.iter() {
        store.put(&mut txn, hash, trie)?;
    }
    txn.commit()?;
    Ok(())
}

// A context for holding lmdb-based test resources
struct LmdbTestContext {
    _temp_dir: TempDir,
    environment: LmdbEnvironment,
    store: LmdbTrieStore,
}

impl LmdbTestContext {
    fn new(tries: &[HashedTestTrie]) -> Result<Self, failure::Error> {
        let _temp_dir = tempdir()?;
        let environment = LmdbEnvironment::new(&_temp_dir.path().to_path_buf(), *TEST_MAP_SIZE)?;
        let store = LmdbTrieStore::new(&environment, None, DatabaseFlags::empty())?;
        put_tries::<_, _, error::Error>(&environment, &store, tries)?;
        Ok(LmdbTestContext {
            _temp_dir,
            environment,
            store,
        })
    }

    fn update(&self, tries: &[HashedTestTrie]) -> Result<(), failure::Error> {
        put_tries::<_, _, error::Error>(&self.environment, &self.store, tries)?;
        Ok(())
    }
}

// A context for holding in-memory test resources
struct InMemoryTestContext {
    environment: InMemoryEnvironment,
    store: InMemoryTrieStore,
}

impl InMemoryTestContext {
    fn new(tries: &[HashedTestTrie]) -> Result<Self, failure::Error> {
        let environment = InMemoryEnvironment::new();
        let store = InMemoryTrieStore::new(&environment);
        put_tries::<_, _, in_memory::Error>(&environment, &store, tries)?;
        Ok(InMemoryTestContext { environment, store })
    }

    fn update(&self, tries: &[HashedTestTrie]) -> Result<(), failure::Error> {
        put_tries::<_, _, in_memory::Error>(&self.environment, &self.store, tries)?;
        Ok(())
    }
}

fn check_leaves_exist<T, S, E>(
    correlation_id: CorrelationId,
    txn: &T,
    store: &S,
    root: &Blake2bHash,
    leaves: &[TestTrie],
) -> Result<Vec<bool>, E>
where
    T: Readable<Handle = S::Handle>,
    S: TrieStore<TestKey, TestValue>,
    S::Error: From<T::Error>,
    E: From<S::Error> + From<contract_ffi::bytesrepr::Error>,
{
    let mut ret = Vec::new();

    for leaf in leaves {
        if let Trie::Leaf { key, value } = leaf {
            let maybe_value: ReadResult<TestValue> =
                read::<_, _, _, _, E>(correlation_id, txn, store, root, key)?;
            ret.push(ReadResult::Found(*value) == maybe_value)
        } else {
            panic!("leaves should only contain leaves")
        }
    }
    Ok(ret)
}

fn check_leaves<'a, R, S, E>(
    correlation_id: CorrelationId,
    environment: &'a R,
    store: &S,
    root: &Blake2bHash,
    present: &[TestTrie],
    absent: &[TestTrie],
) -> Result<(), E>
where
    R: TransactionSource<'a, Handle = S::Handle>,
    S: TrieStore<TestKey, TestValue>,
    S::Error: From<R::Error>,
    E: From<R::Error> + From<S::Error> + From<contract_ffi::bytesrepr::Error>,
{
    let txn: R::ReadTransaction = environment.create_read_txn()?;

    assert!(
        check_leaves_exist::<_, _, E>(correlation_id, &txn, store, root, present)?
            .iter()
            .all(|b| *b)
    );
    assert!(
        check_leaves_exist::<_, _, E>(correlation_id, &txn, store, root, absent)?
            .iter()
            .all(|b| !*b)
    );
    txn.commit()?;
    Ok(())
}

impl InMemoryEnvironment {
    pub fn dump<K, V>(&self) -> Result<HashMap<Blake2bHash, Trie<K, V>>, in_memory::Error>
    where
        K: FromBytes,
        V: FromBytes,
    {
        let data = self.data();
        let guard = data.lock()?;
        guard
            .iter()
            .map(|(hash_bytes, trie_bytes)| {
                let hash: Blake2bHash = bytesrepr::deserialize(hash_bytes)?;
                let trie: Trie<K, V> = bytesrepr::deserialize(trie_bytes)?;
                Ok((hash, trie))
            })
            .collect::<Result<HashMap<Blake2bHash, Trie<K, V>>, bytesrepr::Error>>()
            .map_err(Into::into)
    }
}
