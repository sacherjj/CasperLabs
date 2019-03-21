use common::key::*;
use common::value::*;
use proptest::collection::{btree_map, vec};
use proptest::option;
use proptest::prelude::*;
use shared::newtypes::Blake2bHash;
use std::collections::BTreeMap;
use storage::history::trie;

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

pub fn access_rights_arb() -> impl Strategy<Value = AccessRights> {
    prop_oneof![
        Just(AccessRights::Eqv),
        Just(AccessRights::Read),
        Just(AccessRights::Add),
        Just(AccessRights::Write),
        Just(AccessRights::ReadAdd),
        Just(AccessRights::ReadWrite)
    ]
}

pub fn key_arb() -> impl Strategy<Value = Key> {
    prop_oneof![
        u8_slice_20().prop_map(Key::Account),
        u8_slice_32().prop_map(Key::Hash),
        access_rights_arb()
            .prop_flat_map(|right| { u8_slice_32().prop_map(move |addr| Key::URef(addr, right)) })
    ]
}

pub fn account_arb() -> impl Strategy<Value = common::value::Account> {
    u8_slice_32().prop_flat_map(|b| {
        any::<u64>().prop_flat_map(move |u64arb| {
            uref_map_arb(3).prop_map(move |urefs| common::value::Account::new(b, u64arb, urefs))
        })
    })
}

pub fn contract_arb() -> impl Strategy<Value = common::value::Contract> {
    uref_map_arb(20).prop_flat_map(|urefs| {
        vec(any::<u8>(), 1..1000)
            .prop_map(move |body| common::value::Contract::new(body, urefs.clone()))
    })
}

pub fn u128_arb() -> impl Strategy<Value = common::value::U128> {
    vec(any::<u8>(), 0..16).prop_map(|b| U128::from_little_endian(b.as_slice()))
}

pub fn u256_arb() -> impl Strategy<Value = common::value::U256> {
    vec(any::<u8>(), 0..32).prop_map(|b| U256::from_little_endian(b.as_slice()))
}

pub fn u512_arb() -> impl Strategy<Value = common::value::U512> {
    vec(any::<u8>(), 0..64).prop_map(|b| U512::from_little_endian(b.as_slice()))
}

pub fn value_arb() -> impl Strategy<Value = common::value::Value> {
    prop_oneof![
        (any::<i32>().prop_map(Value::Int32)),
        (vec(any::<u8>(), 1..1000).prop_map(Value::ByteArray)),
        (vec(any::<i32>(), 1..1000).prop_map(Value::ListInt32)),
        ("\\PC*".prop_map(Value::String)),
        (vec(any::<String>(), 1..500).prop_map(Value::ListString)),
        ("\\PC*", key_arb()).prop_map(|(n, k)| Value::NamedKey(n, k)),
        account_arb().prop_map(Value::Account),
        contract_arb().prop_map(Value::Contract),
        u128_arb().prop_map(Value::UInt128),
        u256_arb().prop_map(Value::UInt256),
        u512_arb().prop_map(Value::UInt512)
    ]
}

pub fn blake2b_hash_arb() -> impl Strategy<Value = Blake2bHash> {
    vec(any::<u8>(), 0..1000).prop_map(|b| Blake2bHash::new(&b))
}

pub fn trie_pointer_arb() -> impl Strategy<Value = trie::Pointer> {
    prop_oneof![
        blake2b_hash_arb().prop_map(trie::Pointer::LeafPointer),
        blake2b_hash_arb().prop_map(trie::Pointer::NodePointer)
    ]
}

pub fn trie_pointer_block_arb() -> impl Strategy<Value = trie::PointerBlock> {
    (vec(option::of(trie_pointer_arb()), 256).prop_map(|vec| {
        let mut ret: [Option<trie::Pointer>; 256] = [Default::default(); 256];
        ret.clone_from_slice(vec.as_slice());
        ret.into()
    }))
}

pub fn trie_arb() -> impl Strategy<Value = trie::Trie<Key, Value>> {
    prop_oneof![
        (key_arb(), value_arb()).prop_map(|(key, value)| trie::Trie::Leaf { key, value }),
        trie_pointer_block_arb().prop_map(|pointer_block| trie::Trie::Node {
            pointer_block: Box::new(pointer_block)
        }),
        (vec(any::<u8>(), 0..32), trie_pointer_arb())
            .prop_map(|(affix, pointer)| trie::Trie::Extension { affix, pointer })
    ]
}
