use TreeRootHash;

use common::key::Key;
use error::{Error as StorageError, RootNotFound};
use gs::{DbReader, TrackingCopy};
use std::collections::HashMap;
use transform::Transform;

pub enum CommitResult {
    Success(TreeRootHash),
    Failure(StorageError),
}

pub trait History<R: DbReader> {
    /// Checkouts to the post state of a specific block.
    fn checkout(&self, prestate_hash: TreeRootHash) -> Result<TrackingCopy<R>, RootNotFound>;

    /// Applies changes and returns a new post state hash.
    /// block_hash is used for computing a deterministic and unique keys.
    fn commit(
        &mut self,
        prestate_hash: TreeRootHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, RootNotFound>;
}

pub const EMPTY_ROOT_HASH: [u8; 32] = [0u8; 32];
