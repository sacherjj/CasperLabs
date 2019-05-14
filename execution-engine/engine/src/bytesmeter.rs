use common::bytesrepr::{I32_SIZE, U32_SIZE, U64_SIZE, U8_SIZE};
use common::key::Key;
use common::value::Value;
use std::collections::btree_map::BTreeMap;

/// Returns byte size of the element.
pub trait ByteSize {
    fn byte_size(&self) -> usize;
}

impl ByteSize for Key {
    fn byte_size(&self) -> usize {
        std::mem::size_of::<Key>()
    }
}

impl ByteSize for Value {
    fn byte_size(&self) -> usize {
        match self {
            Value::Int32(_) | Value::UInt128(_) | Value::UInt256(_) | Value::UInt512(_) => {
                std::mem::size_of::<Self>()
            }
            Value::ByteArray(vec) => std::mem::size_of::<Self>() + vec.capacity() * U8_SIZE,
            Value::ListInt32(list) => std::mem::size_of::<Self>() + list.capacity() * I32_SIZE,
            Value::String(s) => std::mem::size_of::<Self>() + s.byte_size(),
            Value::ListString(list) => {
                std::mem::size_of::<Self>() + list.iter().fold(0, |sum, el| sum + el.byte_size())
            }
            Value::NamedKey(name, key) => {
                std::mem::size_of::<Self>() + name.byte_size() + key.byte_size()
            }
            Value::Account(account) => {
                std::mem::size_of::<Self>()
                    + account.pub_key().byte_size()
                    + account.nonce().byte_size()
                    + account.urefs_lookup().byte_size()
            }
            Value::Contract(contract) => {
                std::mem::size_of::<Self>()
                    + contract.bytes().byte_size()
                    + contract.urefs_lookup().byte_size()
            }
        }
    }
}

// NOTE: We're ignoring size of the tree's nodes.
impl<K: ByteSize, V: ByteSize> ByteSize for BTreeMap<K, V> {
    fn byte_size(&self) -> usize {
        self.iter()
            .fold(0, |sum, (k, v)| sum + k.byte_size() + v.byte_size())
    }
}

impl ByteSize for u8 {
    fn byte_size(&self) -> usize {
        U8_SIZE
    }
}

impl ByteSize for i32 {
    fn byte_size(&self) -> usize {
        I32_SIZE
    }
}

impl ByteSize for u32 {
    fn byte_size(&self) -> usize {
        U32_SIZE
    }
}

impl ByteSize for u64 {
    fn byte_size(&self) -> usize {
        U64_SIZE
    }
}

impl<T: ByteSize> ByteSize for [T] {
    fn byte_size(&self) -> usize {
        self.iter().fold(0, |sum, el| sum + el.byte_size())
    }
}

impl ByteSize for String {
    // size_of_val(some_string) always returns 24 bytes,
    // It's the same as the size of empty vector because String is encoded as `Vec<u8>`.
    // but it's a private field so we can't extract it. The closest thing we can get to know
    // String's real size is capacity of the vector it uses to store characters.
    fn byte_size(&self) -> usize {
        self.capacity()
    }
}

#[cfg(test)]
mod tests {
    use bytesmeter::ByteSize;
    use common::bytesrepr::{I32_SIZE, U64_SIZE, U8_SIZE};
    use std::collections::btree_map::BTreeMap;

    fn assert_size<T: ByteSize>(el: T, expected: usize) {
        assert_eq!(el.byte_size(), expected)
    }

    #[test]
    fn size_of_primitives() {
        assert_size(10u8, U8_SIZE);
        assert_size(1i32, I32_SIZE);
        assert_size(1u64, U64_SIZE);
    }

    #[test]
    fn size_of_string() {
        assert_size("Hello".to_owned(), 5)
    }

    #[test]
    fn size_of_slices() {
        let v = vec![1u32, 2, 3, 4];
        let v_ref: &[u32] = &v;
        assert_eq!(v_ref.byte_size(), 16);
    }

    #[test]
    fn size_of_btree() {
        let v = vec![
            (1u8, "A".to_string()),
            (2, "B".to_string()),
            (3, "C".to_string()),
            (4, "D".to_string()),
        ];
        let it_size: usize = 4 * 2; // 4 pairs, each 1-byte key and 1-byte value
        let map: BTreeMap<u8, String> = v.into_iter().collect();
        assert_size(map, it_size);
    }
}
