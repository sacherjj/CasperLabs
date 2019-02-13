use TreeRootHash;

use super::error::Error;
use super::gs::{DbReader, ExecutionEffect, TrackingCopy};

pub trait History<R: DbReader> {
    /// Checkouts to the state that is a result of merging multiple blocks.
    fn checkout_multiple(&self, prestate_hashes: Vec<[u8; 32]>) -> Result<TrackingCopy<R>, Error>;
    /// Checkouts to the post state of a specific block.
    fn checkout(&self, prestate_hash: [u8; 32]) -> Result<TrackingCopy<R>, Error>;
    /// Applies changes and returns a new post state hash.
    /// block_hash is used for computing a deterministic and unique keys.
    fn commit(&mut self, tracking_copy: ExecutionEffect) -> Result<TreeRootHash, Error>;
    /// Returns new root of the tree.
    fn get_root_hash(&self) -> Result<TreeRootHash, Error>;
}
