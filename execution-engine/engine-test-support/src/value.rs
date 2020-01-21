use engine_shared::stored_value::StoredValue;

/// A value stored under a given key on the network.
pub struct Value {
    inner: StoredValue,
}
