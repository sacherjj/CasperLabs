use common::bytesrepr::*;
use gens::gens::*;
use proptest::collection::vec;
use proptest::prelude::*;

fn test_serialization_roundtrip<T: ToBytes + FromBytes + PartialEq + std::fmt::Debug>(
    el: &T,
) -> bool {
    match deserialize::<T>(&ToBytes::to_bytes(el))
        .map(|r| r == *el)
        .ok()
    {
        Some(true) => true,
        Some(false) => false,
        None => false,
    }
}

proptest! {

    #[test]
    fn test_u8(u in any::<u8>()) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_u32(u in any::<u32>()) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_i32(u in any::<i32>()) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_u64(u in any::<u64>()) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_u8_slice_32(s in u8_slice_32()) {
        assert!(test_serialization_roundtrip(&s));
    }

    #[test]
    fn test_vec_u8(u in vec(any::<u8>(), 1..100)) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_vec_i32(u in vec(any::<i32>(), 1..100)) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_vec_vec_u8(u in vec(vec(any::<u8>(), 1..100), 10)) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_uref_map(m in uref_map_arb(20)) {
        assert!(test_serialization_roundtrip(&m));
    }

    #[test]
    fn test_array_u8_32(arr in any::<[u8; 32]>()) {
        assert!(test_serialization_roundtrip(&arr));
    }

    #[test]
    fn test_string(s in "\\PC*") {
        assert!(test_serialization_roundtrip(&s));
    }

    #[test]
    fn test_option(o in proptest::option::of(key_arb())) {
        assert!(test_serialization_roundtrip(&o));
    }

    #[test]
    fn test_unit(unit in Just(())) {
        assert!(test_serialization_roundtrip(&unit));
    }

    #[test]
    fn test_value_account(acct in account_arb()) {
        assert!(test_serialization_roundtrip(&acct));
    }

    #[test]
    fn test_u128_serialization(u in u128_arb()) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_u256_serialization(u in u256_arb()) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_u512_serialization(u in u512_arb()) {
        assert!(test_serialization_roundtrip(&u));
    }

    #[test]
    fn test_key_serialization(key in key_arb()) {
        assert!(test_serialization_roundtrip(&key));
    }

    #[test]
    fn test_value_serialization(v in value_arb()) {
        assert!(test_serialization_roundtrip(&v));
    }

    #[test]
    fn test_blake2b_hash(hash in blake2b_hash_arb()) {
        assert!(test_serialization_roundtrip(&hash));
    }

    #[test]
    fn test_trie_pointer(pointer in trie_pointer_arb()) {
        assert!(test_serialization_roundtrip(&pointer));
    }

    #[test]
    fn test_trie_pointer_block(pointer_block in trie_pointer_block_arb()) {
        assert!(test_serialization_roundtrip(&pointer_block));
    }

    #[test]
    fn test_trie(trie in trie_arb()) {
        assert!(test_serialization_roundtrip(&trie));
    }
}
