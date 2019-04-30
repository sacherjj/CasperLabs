use common::bytesrepr::{self, FromBytes, ToBytes};
use history::trie::{Pointer, Trie};
use history::trie_store::in_memory::{self, InMemoryEnvironment, InMemoryTrieStore};
use history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};
use history::trie_store::{Transaction, TransactionSource, TrieStore};
use lmdb::DatabaseFlags;
use shared::newtypes::Blake2bHash;
use tempfile::{tempdir, TempDir};
use {error, failure};

const TEST_KEY_LENGTH: usize = 4;

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

/// A pairing of a trie element and it's hash.
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

/// Keys have been chosen deliberately and the `create_` functions below depend
/// on these exact definitions.  Values are arbitrary.
const TEST_LEAVES: [TestTrie; 5] = [
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 0]),
        value: TestValue(*b"value0"),
    },
    Trie::Leaf {
        key: TestKey([0u8, 0, 0, 255]),
        value: TestValue(*b"value1"),
    },
    Trie::Leaf {
        key: TestKey([1u8, 0, 1, 0]),
        value: TestValue(*b"value2"),
    },
    Trie::Leaf {
        key: TestKey([1u8, 0, 1, 255]),
        value: TestValue(*b"value3"),
    },
    Trie::Leaf {
        key: TestKey([1u8, 0, 2, 255]),
        value: TestValue(*b"value4"),
    },
];

type TestTrieGenerator = fn() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error>;

const TEST_TRIE_GENERATORS_LENGTH: usize = 6;

const TEST_TRIE_GENERATORS: [TestTrieGenerator; TEST_TRIE_GENERATORS_LENGTH] = [
    create_0_leaf_trie,
    create_1_leaf_trie,
    create_2_leaf_trie,
    create_3_leaf_trie,
    create_4_leaf_trie,
    create_5_leaf_trie,
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

    let non_leaves: Vec<HashedTestTrie> = vec![root];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(non_leaves);
        ret
    };

    Ok((root_hash, tries))
}

fn create_1_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES)?;
    let leaves = (&leaves[0..1]).to_vec();

    let root = HashedTestTrie::new(Trie::node(&[(0, Pointer::LeafPointer(leaves[0].hash))]))?;

    let root_hash: Blake2bHash = root.hash;

    let non_leaves: Vec<HashedTestTrie> = vec![root];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(non_leaves);
        ret
    };

    Ok((root_hash, tries))
}

fn create_2_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES)?;
    let leaves = (&leaves[0..2]).to_vec();

    let node_1 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (255, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let ext_node_1 = HashedTestTrie::new(Trie::extension(
        vec![0u8, 0],
        Pointer::NodePointer(node_1.hash),
    ))?;

    let root = HashedTestTrie::new(Trie::node(&[(0, Pointer::NodePointer(ext_node_1.hash))]))?;

    let root_hash = root.hash;

    let non_leaves: Vec<HashedTestTrie> = vec![node_1, ext_node_1, root];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(non_leaves);
        ret
    };

    Ok((root_hash, tries))
}

fn create_3_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES)?;
    let leaves = (&leaves[0..3]).to_vec();

    let node_1 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (255, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let ext_node_1 = HashedTestTrie::new(Trie::extension(
        vec![0u8, 0],
        Pointer::NodePointer(node_1.hash),
    ))?;

    let root = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(ext_node_1.hash)),
        (1, Pointer::LeafPointer(leaves[2].hash)),
    ]))?;

    let root_hash = root.hash;

    let non_leaves: Vec<HashedTestTrie> = vec![node_1, ext_node_1, root];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(non_leaves);
        ret
    };

    Ok((root_hash, tries))
}

fn create_4_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES)?;
    let leaves = (&leaves[0..4]).to_vec();

    let node_1 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (255, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let node_2 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[2].hash)),
        (255, Pointer::LeafPointer(leaves[3].hash)),
    ]))?;

    let ext_node_1 = HashedTestTrie::new(Trie::extension(
        vec![0u8, 0],
        Pointer::NodePointer(node_1.hash),
    ))?;

    let ext_node_2 = HashedTestTrie::new(Trie::extension(
        vec![0u8, 1],
        Pointer::NodePointer(node_2.hash),
    ))?;

    let root = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(ext_node_1.hash)),
        (1, Pointer::LeafPointer(ext_node_2.hash)),
    ]))?;

    let root_hash = root.hash;

    let non_leaves: Vec<HashedTestTrie> = vec![node_1, node_2, ext_node_1, ext_node_2, root];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(non_leaves);
        ret
    };

    Ok((root_hash, tries))
}

fn create_5_leaf_trie() -> Result<(Blake2bHash, Vec<HashedTestTrie>), bytesrepr::Error> {
    let leaves = hash_test_tries(&TEST_LEAVES)?;

    let node_1 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[0].hash)),
        (255, Pointer::LeafPointer(leaves[1].hash)),
    ]))?;

    let node_2 = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::LeafPointer(leaves[2].hash)),
        (255, Pointer::LeafPointer(leaves[3].hash)),
    ]))?;

    let node_3 = HashedTestTrie::new(Trie::node(&[
        (1, Pointer::NodePointer(node_2.hash)),
        (2, Pointer::LeafPointer(leaves[4].hash)),
    ]))?;

    let ext_node_1 = HashedTestTrie::new(Trie::extension(
        vec![0u8, 0],
        Pointer::NodePointer(node_1.hash),
    ))?;

    let ext_node_3 = HashedTestTrie::new(Trie::extension(
        vec![0u8],
        Pointer::NodePointer(node_3.hash),
    ))?;

    let root = HashedTestTrie::new(Trie::node(&[
        (0, Pointer::NodePointer(ext_node_1.hash)),
        (1, Pointer::LeafPointer(ext_node_3.hash)),
    ]))?;

    let root_hash: Blake2bHash = root.hash;

    let non_leaves: Vec<HashedTestTrie> =
        vec![node_1, node_2, node_3, ext_node_1, ext_node_3, root];

    let tries: Vec<HashedTestTrie> = {
        let mut ret = Vec::new();
        ret.extend(leaves);
        ret.extend(non_leaves);
        ret
    };

    Ok((root_hash, tries))
}

fn put_tries<'a, R, S, E>(environment: &'a R, store: &S, tries: &[HashedTestTrie]) -> Result<(), E>
where
    R: TransactionSource<'a, Handle = S::Handle>,
    S: TrieStore<TestKey, TestValue>,
    S::Error: From<R::Error>,
    E: From<R::Error> + From<S::Error> + From<common::bytesrepr::Error>,
{
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
    states: Vec<Blake2bHash>,
}

impl LmdbTestContext {
    fn new(root_hash: Blake2bHash, tries: &[HashedTestTrie]) -> Result<Self, failure::Error> {
        let _temp_dir = tempdir()?;
        let environment = LmdbEnvironment::new(&_temp_dir.path().to_path_buf())?;
        let store = LmdbTrieStore::new(&environment, None, DatabaseFlags::empty())?;
        put_tries::<LmdbEnvironment, LmdbTrieStore, error::Error>(&environment, &store, tries)?;
        let states = vec![root_hash];
        Ok(LmdbTestContext {
            _temp_dir,
            environment,
            store,
            states,
        })
    }

    fn empty() -> Result<Self, failure::Error> {
        let root = HashedTestTrie::new(Trie::Node {
            pointer_block: Default::default(),
        })?;
        LmdbTestContext::new(root.hash, &[root])
    }

    fn push(
        &mut self,
        root_hash: Blake2bHash,
        tries: &[HashedTestTrie],
    ) -> Result<(), failure::Error> {
        put_tries::<LmdbEnvironment, LmdbTrieStore, error::Error>(
            &self.environment,
            &self.store,
            tries,
        )?;
        self.states.push(root_hash);
        Ok(())
    }
}

// A context for holding in-memory test resources
struct InMemoryTestContext {
    environment: InMemoryEnvironment,
    store: InMemoryTrieStore,
    states: Vec<Blake2bHash>,
}

impl InMemoryTestContext {
    fn new(root_hash: Blake2bHash, tries: &[HashedTestTrie]) -> Result<Self, failure::Error> {
        let environment = InMemoryEnvironment::new();
        let store = InMemoryTrieStore::new(&environment);
        put_tries::<InMemoryEnvironment, InMemoryTrieStore, in_memory::Error>(
            &environment,
            &store,
            tries,
        )?;
        let states = vec![root_hash];
        Ok(InMemoryTestContext {
            environment,
            store,
            states,
        })
    }

    fn empty() -> Result<Self, failure::Error> {
        let root = HashedTestTrie::new(Trie::Node {
            pointer_block: Default::default(),
        })?;
        InMemoryTestContext::new(root.hash, &[root])
    }

    fn push(
        &mut self,
        root_hash: Blake2bHash,
        tries: &[HashedTestTrie],
    ) -> Result<(), failure::Error> {
        put_tries::<InMemoryEnvironment, InMemoryTrieStore, in_memory::Error>(
            &self.environment,
            &self.store,
            tries,
        )?;
        self.states.push(root_hash);
        Ok(())
    }
}

mod read {
    //! This module contains tests for [`StateReader::read`].
    //!
    //! Our primary goal here is to test this functionality in isolation.
    //! Therefore, we manually construct test tries from a well-known set of
    //! leaves called [`TEST_LEAVES`](super::TEST_LEAVES), each of which represents a value we are
    //! trying to store in the trie at a given key.
    //!
    //! We use two strategies for testing.  See the [`partial_tries`] and
    //! [`full_tries`] modules for more info.

    use super::*;
    use error;
    use history::trie_store::operations::{read, ReadResult};
    use history::trie_store::{in_memory, TransactionSource, TrieStore};
    use shared::newtypes::Blake2bHash;

    fn check_leaves<'a, R, S, E>(
        environment: &'a R,
        store: &S,
        root: &Blake2bHash,
        num_leaves: usize,
    ) -> Result<(), E>
    where
        R: TransactionSource<'a, Handle = S::Handle>,
        S: TrieStore<TestKey, TestValue>,
        S::Error: From<R::Error>,
        E: From<R::Error> + From<S::Error> + From<common::bytesrepr::Error>,
    {
        let txn: R::ReadTransaction = environment.create_read_txn()?;
        let test_leaves = TEST_LEAVES;
        let (used, unused) = test_leaves.split_at(num_leaves);

        for leaf in used.iter() {
            match leaf {
                Trie::Leaf { key, value } => {
                    let maybe_value: ReadResult<TestValue> =
                        read::<TestKey, TestValue, R::ReadTransaction, S, E>(
                            &txn, store, root, key,
                        )?;
                    assert_eq!(ReadResult::Found(*value), maybe_value);
                }
                _ => panic!("TEST_LEAVES should only contain leaves"),
            }
        }

        for leaf in unused.iter() {
            match leaf {
                Trie::Leaf { key, .. } => {
                    let maybe_value: ReadResult<TestValue> =
                        read::<TestKey, TestValue, R::ReadTransaction, S, E>(
                            &txn, store, root, key,
                        )?;
                    assert_eq!(ReadResult::NotFound, maybe_value);
                }
                _ => panic!("TEST_LEAVES should only contain leaves"),
            }
        }

        txn.commit()?;
        Ok(())
    }

    mod partial_tries {
        //! Here we construct 6 separate "partial" tries, increasing in size
        //! from 0 to 5 leaves.  Each of these tries contains no past history,
        //! only a single a root to read from.  The tests check that we can read
        //! only the expected set of leaves from the trie from this single root.

        use super::*;

        #[test]
        #[allow(clippy::needless_range_loop)]
        fn lmdb_reads_from_n_leaf_partial_trie_had_expected_results() {
            for num_leaves in 0..TEST_TRIE_GENERATORS_LENGTH {
                let (root_hash, tries) = TEST_TRIE_GENERATORS[num_leaves]().unwrap();
                let context = LmdbTestContext::new(root_hash, &tries).unwrap();
                check_leaves::<LmdbEnvironment, LmdbTrieStore, error::Error>(
                    &context.environment,
                    &context.store,
                    &context.states[0],
                    num_leaves,
                )
                .unwrap();
            }
        }

        #[test]
        #[allow(clippy::needless_range_loop)]
        fn in_memory_reads_from_n_leaf_partial_trie_had_expected_results() {
            for num_leaves in 0..TEST_TRIE_GENERATORS_LENGTH {
                let (root_hash, tries) = TEST_TRIE_GENERATORS[num_leaves]().unwrap();
                let context = InMemoryTestContext::new(root_hash, &tries).unwrap();
                check_leaves::<InMemoryEnvironment, InMemoryTrieStore, in_memory::Error>(
                    &context.environment,
                    &context.store,
                    &context.states[0],
                    num_leaves,
                )
                .unwrap();
            }
        }
    }

    mod full_tries {
        //! Here we construct a series of 6 "full" tries, increasing in size
        //! from 0 to 5 leaves.  Each trie contains the history from preceding
        //! tries in this series, and past history can be read from the roots of
        //! each preceding trie.  The tests check that we can read only the
        //! expected set of leaves from the trie at the current root and all past
        //! roots.

        use super::*;

        #[test]
        #[allow(clippy::needless_range_loop)]
        fn lmdb_reads_from_n_leaf_full_trie_had_expected_results() {
            let mut context = LmdbTestContext::empty().unwrap();

            for i in 1..TEST_TRIE_GENERATORS_LENGTH {
                let (root_hash, tries) = TEST_TRIE_GENERATORS[i]().unwrap();
                context.push(root_hash, &tries).unwrap();

                for j in 0..i {
                    check_leaves::<LmdbEnvironment, LmdbTrieStore, error::Error>(
                        &context.environment,
                        &context.store,
                        &context.states[j],
                        j,
                    )
                    .unwrap();
                }
            }
        }

        #[test]
        #[allow(clippy::needless_range_loop)]
        fn in_memory_reads_from_n_leaf_full_trie_had_expected_results() {
            let mut context = InMemoryTestContext::empty().unwrap();

            for i in 1..TEST_TRIE_GENERATORS_LENGTH {
                let (root_hash, tries) = TEST_TRIE_GENERATORS[i]().unwrap();
                context.push(root_hash, &tries).unwrap();

                for j in 0..i {
                    check_leaves::<InMemoryEnvironment, InMemoryTrieStore, in_memory::Error>(
                        &context.environment,
                        &context.store,
                        &context.states[j],
                        j,
                    )
                    .unwrap();
                }
            }
        }
    }
}
