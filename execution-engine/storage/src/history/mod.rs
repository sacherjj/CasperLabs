use common::key::Key;
use error::GlobalStateError;
use gs::{DbReader, TrackingCopy};
use shared::newtypes::Blake2bHash;
use std::collections::HashMap;
use transform::Transform;

// needs to be public for use in the gens crate
pub mod trie;

pub enum CommitResult {
    Success(Blake2bHash),
    Failure(GlobalStateError),
}

pub trait History<R: DbReader> {
    type Error;

    /// Checkouts to the post state of a specific block.
    fn checkout(&self, prestate_hash: Blake2bHash) -> Result<TrackingCopy<R>, Self::Error>;

    /// Applies changes and returns a new post state hash.
    /// block_hash is used for computing a deterministic and unique keys.
    fn commit(
        &mut self,
        prestate_hash: Blake2bHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, Self::Error>;
}
