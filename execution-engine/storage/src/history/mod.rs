use common::key::Key;
use common::value::Value;
use global_state::StateReader;
use history::trie::Trie;
use history::trie_store::operations::{read, write, ReadResult, WriteResult};
use history::trie_store::{Transaction, TransactionSource, TrieStore};
use shared::newtypes::Blake2bHash;
use std::collections::HashMap;
use std::hash::BuildHasher;
use transform::{self, Transform, TypeMismatch};

// needs to be public for use in the gens crate
pub mod trie;
pub mod trie_store;

#[derive(Debug)]
pub enum CommitResult {
    RootNotFound,
    Success(Blake2bHash),
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
    Overflow,
}

impl From<transform::Error> for CommitResult {
    fn from(error: transform::Error) -> Self {
        match error {
            transform::Error::TypeMismatch(type_mismatch) => {
                CommitResult::TypeMismatch(type_mismatch)
            }
            transform::Error::Overflow => CommitResult::Overflow,
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
        prestate_hash: Blake2bHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, Self::Error>;
}

pub fn commit<'a, R, S, H, E>(
    environment: &'a R,
    store: &S,
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

    for (key, transform) in effects.into_iter() {
        let read_result = read::<_, _, _, _, E>(&txn, store, &current_root, &key)?;

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

        match write::<_, _, _, _, E>(&mut txn, store, &current_root, &key, &value)? {
            WriteResult::Written(root_hash) => {
                current_root = root_hash;
            }
            WriteResult::AlreadyExists => (),
            _x @ WriteResult::RootNotFound => panic!(stringify!(_x)),
        }
    }

    txn.commit()?;
    Ok(CommitResult::Success(current_root))
}
