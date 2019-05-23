pub mod in_memory;
pub mod lmdb;

use std::collections::HashMap;
use std::hash::BuildHasher;
use std::time::SystemTime;

use common::key::Key;
use common::value::Value;
use shared::capture_duration;
use shared::capture_elapsed;
use shared::capture_gauge;
use shared::newtypes::{Blake2bHash, CorrelationId};
use shared::transform::{self, Transform, TypeMismatch};

use trie::Trie;
use trie_store::operations::{read, write, ReadResult, WriteResult};
use trie_store::{Transaction, TransactionSource, TrieStore};

/// A reader of state
pub trait StateReader<K, V> {
    /// An error which occurs when reading state
    type Error;

    /// Returns the state value from the corresponding key
    fn read(&self, correlation_id: CorrelationId, key: &K) -> Result<Option<V>, Self::Error>;
}

#[derive(Debug)]
pub enum CommitResult {
    RootNotFound,
    Success(Blake2bHash),
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
}

impl From<transform::Error> for CommitResult {
    fn from(error: transform::Error) -> Self {
        match error {
            transform::Error::TypeMismatch(type_mismatch) => {
                CommitResult::TypeMismatch(type_mismatch)
            }
        }
    }
}

pub trait History {
    type Error;
    type Reader: StateReader<Key, Value, Error = Self::Error>;

    /// Checkouts to the post state of a specific block.
    fn checkout(&self, prestate_hash: Blake2bHash) -> Result<Option<Self::Reader>, Self::Error>;

    /// Applies changes and returns a new post state hash.
    /// block_hash is used for computing a deterministic and unique keys.
    fn commit(
        &mut self,
        correlation_id: CorrelationId,
        prestate_hash: Blake2bHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, Self::Error>;
}

const GLOBAL_STATE_COMMIT_READS: &str = "global_state_commit_reads";
const GLOBAL_STATE_COMMIT_WRITES: &str = "global_state_commit_writes";
const GLOBAL_STATE_COMMIT_DURATION: &str = "global_state_commit_duration";
const GLOBAL_STATE_COMMIT_READ_DURATION: &str = "global_state_commit_read_duration";
const GLOBAL_STATE_COMMIT_WRITE_DURATION: &str = "global_state_commit_write_duration";
const COMMIT: &str = "commit";

pub fn commit<'a, R, S, H, E>(
    environment: &'a R,
    store: &S,
    correlation_id: CorrelationId,
    prestate_hash: Blake2bHash,
    effects: HashMap<Key, Transform, H>,
) -> Result<CommitResult, E>
where
    R: TransactionSource<'a, Handle = S::Handle>,
    S: TrieStore<Key, Value>,
    S::Error: From<R::Error>,
    E: From<R::Error> + From<S::Error> + From<common::bytesrepr::Error>,
    H: BuildHasher,
{
    let mut txn = environment.create_read_write_txn()?;
    let mut current_root = prestate_hash;

    let maybe_root: Option<Trie<Key, Value>> = store.get(&txn, &current_root)?;

    if maybe_root.is_none() {
        return Ok(CommitResult::RootNotFound);
    };

    let start = SystemTime::now();
    let mut reads: i32 = 0;
    let mut writes: i32 = 0;

    for (key, transform) in effects.into_iter() {
        let read_result = capture_duration!(
            correlation_id,
            GLOBAL_STATE_COMMIT_READ_DURATION,
            COMMIT,
            read::<_, _, _, _, E>(correlation_id, &txn, store, &current_root, &key)?
        );

        reads += 1;

        let value = match (read_result, transform) {
            (ReadResult::NotFound, Transform::Write(new_value)) => new_value,
            (ReadResult::NotFound, _) => {
                return Ok(CommitResult::KeyNotFound(key));
            }
            (ReadResult::Found(current_value), transform) => match transform.apply(current_value) {
                Ok(updated_value) => updated_value,
                Err(err) => return Ok(err.into()),
            },
            _x @ (ReadResult::RootNotFound, _) => panic!(stringify!(_x._1)),
        };

        let write_result = capture_duration!(
            correlation_id,
            GLOBAL_STATE_COMMIT_WRITE_DURATION,
            COMMIT,
            write::<_, _, _, _, E>(correlation_id, &mut txn, store, &current_root, &key, &value)?
        );

        match write_result {
            WriteResult::Written(root_hash) => {
                current_root = root_hash;
                writes += 1;
            }
            WriteResult::AlreadyExists => (),
            _x @ WriteResult::RootNotFound => panic!(stringify!(_x)),
        }
    }

    txn.commit()?;

    capture_elapsed!(correlation_id, GLOBAL_STATE_COMMIT_DURATION, COMMIT, start);

    capture_gauge!(
        correlation_id,
        GLOBAL_STATE_COMMIT_READS,
        COMMIT,
        f64::from(reads)
    );

    capture_gauge!(
        correlation_id,
        GLOBAL_STATE_COMMIT_WRITES,
        COMMIT,
        f64::from(writes)
    );

    Ok(CommitResult::Success(current_root))
}
