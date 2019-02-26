use common::key::*;
use common::value::*;
use proptest::collection::{btree_map, vec};
use proptest::prelude::*;
use std::collections::BTreeMap;

pub fn u8_slice_20() -> impl Strategy<Value = [u8; 20]> {
    vec(any::<u8>(), 20).prop_map(|b| {
        let mut res = [0u8; 20];
        res.clone_from_slice(b.as_slice());
        res
    })
}

pub fn u8_slice_32() -> impl Strategy<Value = [u8; 32]> {
    vec(any::<u8>(), 32).prop_map(|b| {
        let mut res = [0u8; 32];
        res.clone_from_slice(b.as_slice());
        res
    })
}

pub fn uref_map_arb(depth: usize) -> impl Strategy<Value = BTreeMap<String, Key>> {
    btree_map("\\PC*", key_arb(), depth)
}

pub fn key_arb() -> impl Strategy<Value = Key> {
    prop_oneof![
        u8_slice_20().prop_map(Key::Account),
        u8_slice_32().prop_map(Key::Hash),
        u8_slice_32().prop_map(Key::URef)
    ]
}

pub fn account_arb() -> impl Strategy<Value = common::value::Account> {
    u8_slice_32().prop_flat_map(|b| {
        any::<u64>().prop_flat_map(move |u64arb| {
            uref_map_arb(3).prop_map(move |urefs| common::value::Account::new(b, u64arb, urefs))
        })
    })
}

pub fn contract_arb() -> impl Strategy<Value = common::value::Value> {
    uref_map_arb(20).prop_flat_map(|urefs| {
        vec(any::<u8>(), 1..1000).prop_map(move |body| Value::Contract {
            bytes: body,
            known_urefs: urefs.clone(),
        })
    })
}

pub fn value_arb() -> impl Strategy<Value = common::value::Value> {
    prop_oneof![
        (any::<i32>().prop_map(Value::Int32)),
        (vec(any::<u8>(), 1..1000).prop_map(Value::ByteArray)),
        (vec(any::<i32>(), 1..1000).prop_map(Value::ListInt32)),
        ("\\PC*".prop_map(Value::String)),
        (vec(any::<String>(), 1..500).prop_map(Value::ListString)),
        ("\\PC*", key_arb()).prop_map(|(n, k)| Value::NamedKey(n, k)),
        account_arb().prop_map(Value::Acct),
        contract_arb()
    ]
}
