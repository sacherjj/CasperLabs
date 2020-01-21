use engine_core::engine_state::query::QueryRequest;

use crate::Address;

/// A query which can be made for a given [`Value`](struct.Value.html) in the
/// [`TestContext`](struct.TestContext.html).
pub struct Query {
    inner: QueryRequest,
}

impl Query {
    /// Constructs a new `Query`.
    pub fn new<T: AsRef<str>>(key: Address, path: &[T]) -> Self {
        unimplemented!()
    }
}
