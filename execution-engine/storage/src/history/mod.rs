use TreeRootHash;

use super::error::Error;
use super::gs::{DbReader, TrackingCopy};
use super::transform::Transform;
use common::key::Key;
use std::collections::HashMap;

pub trait History<R: DbReader> {
    /// Checkouts to the post state of a specific block.
    fn checkout(&self, prestate_hash: TreeRootHash) -> Result<TrackingCopy<R>, Error>;

    /// Applies changes and returns a new post state hash.
    /// block_hash is used for computing a deterministic and unique keys.
    fn commit(
        &mut self,
        prestate_hash: TreeRootHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<TreeRootHash, Error>;
}
