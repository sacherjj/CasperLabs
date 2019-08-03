use std::collections::HashMap;

use contract_ffi::key::Key;
use engine_shared::transform::Transform;

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
