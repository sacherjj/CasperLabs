use super::error::Error;
use super::op::Op;
use super::transform::Transform;
use crate::common::key::Key;
use crate::common::value::Value;
use std::collections::HashMap;

pub mod inmem;

#[derive(Debug)]
pub struct ExecutionEffect(pub HashMap<Key, Op>, pub HashMap<Key, Transform>);

pub trait GlobalState<T: TrackingCopy> {
    fn apply(&mut self, k: Key, t: Transform) -> Result<(), Error>;
    fn get(&self, k: &Key) -> Result<&Value, Error>;
    fn tracking_copy(&self) -> T;
}

pub trait TrackingCopy {
    fn new_uref(&mut self) -> Key;
    fn read(&mut self, k: Key) -> Result<&Value, Error>;
    fn write(&mut self, k: Key, v: Value) -> Result<(), Error>;
    fn add(&mut self, k: Key, v: Value) -> Result<(), Error>;
    fn effect(&self) -> ExecutionEffect;
}
