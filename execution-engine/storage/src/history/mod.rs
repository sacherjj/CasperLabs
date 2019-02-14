use TreeRootHash;

use super::error::Error;
use super::gs::{DbReader, ExecutionEffect, TrackingCopy};
use common::key::Key;
use super::transform::Transform;

pub trait History<R: DbReader> {
    /// Checkouts to the post state of a specific block.
    fn checkout(&self, prestate_hash: [u8; 32]) -> Result<TrackingCopy<R>, Error>;
    /// Applies changes and returns a new post state hash.
    /// block_hash is used for computing a deterministic and unique keys.
    fn commit(&self, effects: std::collections::HashMap<Key, Transform>) -> Result<TreeRootHash, Error>;
    /// Returns new root of the tree.
    fn get_root_hash(&self) -> Result<TreeRootHash, Error>;
}
