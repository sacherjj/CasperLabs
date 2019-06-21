use std::collections::HashMap;

use common::key::Key;
use shared::transform::Transform;

use super::op::Op;

#[derive(Debug, Default, PartialEq, Eq)]
pub struct ExecutionEffect {
    pub ops: HashMap<Key, Op>,
    pub transforms: HashMap<Key, Transform>,
}

impl ExecutionEffect {
    pub fn new(ops: HashMap<Key, Op>, transforms: HashMap<Key, Transform>) -> Self {
        ExecutionEffect { ops, transforms }
    }
}
