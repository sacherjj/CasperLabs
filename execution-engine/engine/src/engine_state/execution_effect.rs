use std::collections::HashMap;

use common::key::Key;
use shared::transform::Transform;

use super::op::Op;

#[derive(Debug, Default)]
pub struct ExecutionEffect(pub HashMap<Key, Op>, pub HashMap<Key, Transform>);
