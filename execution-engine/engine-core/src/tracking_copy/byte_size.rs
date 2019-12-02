use std::{collections::BTreeMap, mem};

use contract_ffi::key::Key;
use engine_shared::{account::Account, contract::Contract, stored_value::StoredValue};

/// Returns byte size of the element - both heap size and stack size.
pub trait ByteSize {
    fn byte_size(&self) -> usize;
}

impl ByteSize for Key {
    fn byte_size(&self) -> usize {
        mem::size_of::<Self>() + self.heap_size()
    }
}

impl ByteSize for Account {
    fn byte_size(&self) -> usize {
        mem::size_of::<Self>() + self.heap_size() + self.named_keys().byte_size()
    }
}

impl ByteSize for Contract {
    fn byte_size(&self) -> usize {
        mem::size_of::<Self>()
            + self.heap_size()
            + self.named_keys().byte_size()
            + self.bytes().len()
    }
}

impl ByteSize for String {
    fn byte_size(&self) -> usize {
        mem::size_of::<Self>() + self.heap_size()
    }
}

impl<K: HeapSizeOf, V: HeapSizeOf> ByteSize for BTreeMap<K, V> {
    fn byte_size(&self) -> usize {
        mem::size_of::<BTreeMap<K, V>>()
            + self.heap_size()
            + self.len() * (mem::size_of::<K>() + mem::size_of::<V>())
    }
}

// TODO(Fraser) - delete.  Leaving for now as reference in case we need to implement for CLValue
// or StoredValue
//impl ByteSize for Value {
//    fn byte_size(&self) -> usize {
//        mem::size_of::<Self>()
//            + match self {
//                Value::Int32(_)
//                | Value::UInt128(_)
//                | Value::UInt256(_)
//                | Value::UInt512(_)
//                | Value::Unit
//                | Value::UInt64(_) => 0,
//                Value::ByteArray(vec) => mem::size_of::<Vec<u8>>() + vec.capacity(),
//                Value::ListInt32(list) => {
//                    mem::size_of::<Vec<i32>>() + list.capacity() * I32_SIZE
//                }
//                Value::String(s) => s.byte_size(),
//                Value::ListString(list) => list.iter().fold(0, |sum, el| sum + el.byte_size()),
//                // NOTE: We don't measure `key` as its size will be returned with
//                // `mem::size_of::<Value>()` call Similarly, we don't measure
//                // stack size of name, account and contract as they're accounted for
//                // in the `mem::size_of::<Self>()` call.
//                Value::Key(_) => 0,
//                Value::NamedKey(name, _key) => name.heap_size(),
//                Value::Account(account) => account.heap_size(),
//                Value::Contract(contract) => contract.heap_size(),
//            }
//    }
//}

// TODO(Fraser) - fix
impl ByteSize for StoredValue {
    fn byte_size(&self) -> usize {
        mem::size_of::<Self>()
            + match self {
                StoredValue::CLValue(cl_value) => cl_value.serialized_len(),
                StoredValue::Account(account) => account.heap_size(),
                StoredValue::Contract(contract) => contract.heap_size(),
            }
    }
}

/// Returns heap size of the value.
/// Note it's different from [ByteSize] that returns both heap and stack size.
pub trait HeapSizeOf {
    fn heap_size(&self) -> usize;
}

impl HeapSizeOf for Key {
    fn heap_size(&self) -> usize {
        0
    }
}

impl HeapSizeOf for Account {
    fn heap_size(&self) -> usize {
        self.named_keys().heap_size()
    }
}

impl HeapSizeOf for Contract {
    fn heap_size(&self) -> usize {
        self.named_keys().heap_size() + self.bytes().len()
    }
}

// NOTE: We're ignoring size of the tree's nodes.
impl<K: HeapSizeOf, V: HeapSizeOf> HeapSizeOf for BTreeMap<K, V> {
    fn heap_size(&self) -> usize {
        self.iter()
            .fold(0, |sum, (k, v)| sum + k.heap_size() + v.heap_size())
    }
}

impl<T: HeapSizeOf> ByteSize for [T] {
    fn byte_size(&self) -> usize {
        self.iter()
            .fold(0, |sum, el| sum + mem::size_of::<T>() + el.heap_size())
    }
}

impl HeapSizeOf for String {
    fn heap_size(&self) -> usize {
        self.capacity()
    }
}

#[cfg(test)]
mod tests {
    use std::{collections::BTreeMap, mem};

    use contract_ffi::key::Key;

    use super::ByteSize;

    fn assert_byte_size<T: ByteSize>(el: T, expected: usize) {
        assert_eq!(el.byte_size(), expected)
    }

    #[test]
    fn byte_size_of_string() {
        assert_byte_size("Hello".to_owned(), 5 + mem::size_of::<String>())
    }

    #[test]
    fn byte_size_of_map() {
        let v = vec![
            (Key::Hash([1u8; 32]), "A".to_string()),
            (Key::Hash([2u8; 32]), "B".to_string()),
            (Key::Hash([3u8; 32]), "C".to_string()),
            (Key::Hash([4u8; 32]), "D".to_string()),
        ];
        let it_size: usize = mem::size_of::<BTreeMap<Key, String>>()
            + 4 * (mem::size_of::<Key>() + mem::size_of::<String>() + 1);
        let map: BTreeMap<Key, String> = v.into_iter().collect();
        assert_byte_size(map, it_size);
    }
}
