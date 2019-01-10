#[macro_use]
extern crate proptest;
extern crate common;
extern crate serde;

use common::de::from_bytes;
use common::key::*;
use common::ser::{to_bytes, Error};
use common::value::*;
use proptest::collection::{btree_map, vec};
use proptest::prelude::*;
use serde::de::DeserializeOwned;
use serde::ser::Serialize;
use std::collections::BTreeMap;

fn u8_slice_20() -> impl Strategy<Value = [u8; 20]> {
    vec(any::<u8>(), 20).prop_map(|b| {
        let mut res = [0u8; 20];
        res.clone_from_slice(b.as_slice());
        res
    })
}

fn u8_slice_32() -> impl Strategy<Value = [u8; 32]> {
    vec(any::<u8>(), 32).prop_map(|b| {
        let mut res = [0u8; 32];
        res.clone_from_slice(b.as_slice());
        res
    })
}

fn uref_map_arb(depth: usize) -> impl Strategy<Value = BTreeMap<String, Key>> {
    btree_map("\\PC*", key_arb(), depth)
}

fn key_arb() -> impl Strategy<Value = Key> {
    prop_oneof![
        u8_slice_20().prop_map(|b| Key::Account(b)),
        u8_slice_32().prop_map(|b| Key::Hash(b)),
        u8_slice_32().prop_map(|b| Key::URef(b))
    ]
}

fn account_arb() -> impl Strategy<Value = common::value::Account> {
    u8_slice_32().prop_flat_map(|b| {
        any::<u64>().prop_flat_map(move |u64arb| {
            uref_map_arb(3).prop_map(move |urefs| common::value::Account::new(b, u64arb, urefs))
        })
    })
}

fn contract_arb() -> impl Strategy<Value = common::value::Value> {
    uref_map_arb(20).prop_flat_map(|urefs| {
        vec(any::<u8>(), 1..1000).prop_map(move |body| Value::Contract {
            bytes: body,
            known_urefs: urefs.clone(),
        })
    })
}

fn value_arb() -> impl Strategy<Value = common::value::Value> {
    prop_oneof![
        (any::<i32>().prop_map(|i| Value::Int32(i))),
        (vec(any::<u8>(), 1..1000).prop_map(|b| Value::ByteArray(b))),
        (vec(any::<i32>(), 1..1000).prop_map(|b| Value::ListInt32(b))),
        ("\\PC*".prop_map(|s| Value::String(s))),
        (vec(any::<String>(), 1..500).prop_map(|s| Value::ListString(s))),
        ("\\PC*", key_arb()).prop_map(|(n, k)| Value::NamedKey(n, k)),
        account_arb().prop_map(|acc| Value::Acct(acc)),
        contract_arb()
    ]
}

fn test_serialization_roundtrip<T: Serialize + DeserializeOwned + PartialEq + std::fmt::Debug>(
    el: &T,
) -> bool {
    let bytes = to_bytes(el);
    let de: Result<T, Error> = from_bytes(&bytes);
    match de.map(|r| r == *el).ok() {
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
   fn test_key_serialization(key in key_arb()) {
       assert!(test_serialization_roundtrip(&key));
   }

    #[test]
    fn test_value_serialization(v in value_arb()) {
        assert!(test_serialization_roundtrip(&v));
    }
}
