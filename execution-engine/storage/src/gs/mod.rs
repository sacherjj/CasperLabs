use super::error::Error;
use super::op::Op;
use super::transform::Transform;
use crate::common::key::Key;
use crate::common::value::Value;
use std::collections::HashMap;

pub mod inmem;
pub mod lmdb;
pub mod trackingcopy;

pub use self::trackingcopy::TrackingCopy;

#[derive(Debug)]
pub struct ExecutionEffect(pub HashMap<Key, Op>, pub HashMap<Key, Transform>);

pub trait DbReader {
    fn get(&self, k: &Key) -> Result<Value, Error>;
}
