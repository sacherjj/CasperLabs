//! Contains functions for generating arbitrary values for use by [`Proptest`](https://crates.io/crates/proptest).

use alloc::{collections::BTreeMap, string::String, vec};

use proptest::{
    array, bits,
    collection::{btree_map, vec},
    option,
    prelude::*,
    result,
};

use crate::{
    account::{PublicKey, Weight},
    AccessRights, CLType, CLValue, Key, Phase, ProtocolVersion, SemVer, URef, U128, U256, U512,
};

pub fn u8_slice_32() -> impl Strategy<Value = [u8; 32]> {
    vec(any::<u8>(), 32).prop_map(|b| {
        let mut res = [0u8; 32];
        res.clone_from_slice(b.as_slice());
        res
    })
}

pub fn named_keys_arb(depth: usize) -> impl Strategy<Value = BTreeMap<String, Key>> {
    btree_map("\\PC*", key_arb(), depth)
}

pub fn access_rights_arb() -> impl Strategy<Value = AccessRights> {
    prop_oneof![
        Just(AccessRights::READ),
        Just(AccessRights::ADD),
        Just(AccessRights::WRITE),
        Just(AccessRights::READ_ADD),
        Just(AccessRights::READ_WRITE),
        Just(AccessRights::ADD_WRITE),
        Just(AccessRights::READ_ADD_WRITE),
    ]
}

pub fn phase_arb() -> impl Strategy<Value = Phase> {
    prop_oneof![
        Just(Phase::Payment),
        Just(Phase::Session),
        Just(Phase::FinalizePayment),
    ]
}

pub fn uref_arb() -> impl Strategy<Value = URef> {
    (
        array::uniform32(bits::u8::ANY),
        option::weighted(option::Probability::new(0.8), access_rights_arb()),
    )
        .prop_map(|(id, maybe_access_rights)| URef::unsafe_new(id, maybe_access_rights))
}

pub fn key_arb() -> impl Strategy<Value = Key> {
    prop_oneof![
        u8_slice_32().prop_map(Key::Account),
        u8_slice_32().prop_map(Key::Hash),
        uref_arb().prop_map(Key::URef),
        (u8_slice_32(), u8_slice_32()).prop_map(|(seed, key)| Key::local(seed, &key))
    ]
}

pub fn public_key_arb() -> impl Strategy<Value = PublicKey> {
    u8_slice_32().prop_map(Into::into)
}

pub fn weight_arb() -> impl Strategy<Value = Weight> {
    any::<u8>().prop_map(Weight::new)
}

pub fn sem_ver_arb() -> impl Strategy<Value = SemVer> {
    (any::<u32>(), any::<u32>(), any::<u32>())
        .prop_map(|(major, minor, patch)| SemVer::new(major, minor, patch))
}

pub fn protocol_version_arb() -> impl Strategy<Value = ProtocolVersion> {
    sem_ver_arb().prop_map(ProtocolVersion::new)
}

pub fn u128_arb() -> impl Strategy<Value = U128> {
    vec(any::<u8>(), 0..16).prop_map(|b| U128::from_little_endian(b.as_slice()))
}

pub fn u256_arb() -> impl Strategy<Value = U256> {
    vec(any::<u8>(), 0..32).prop_map(|b| U256::from_little_endian(b.as_slice()))
}

pub fn u512_arb() -> impl Strategy<Value = U512> {
    vec(any::<u8>(), 0..64).prop_map(|b| U512::from_little_endian(b.as_slice()))
}

pub fn cl_value_arb() -> impl Strategy<Value = CLValue> {
    // If compiler brings you here it most probably means you've added a variant to `CLType` enum
    // but forgot to add generator for it.
    let stub: Option<CLType> = None;
    if let Some(cl_type) = stub {
        match cl_type {
            CLType::Bool
            | CLType::I32
            | CLType::I64
            | CLType::U8
            | CLType::U32
            | CLType::U64
            | CLType::U128
            | CLType::U256
            | CLType::U512
            | CLType::Unit
            | CLType::String
            | CLType::Key
            | CLType::URef
            | CLType::Option(_)
            | CLType::List(_)
            | CLType::FixedList(..)
            | CLType::Result { .. }
            | CLType::Map { .. }
            | CLType::Tuple1(_)
            | CLType::Tuple2(_)
            | CLType::Tuple3(_)
            | CLType::Any => (),
        }
    };

    prop_oneof![
        Just(CLValue::from_t(()).expect("should create CLValue")),
        any::<bool>().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        any::<i32>().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        any::<i64>().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        any::<u8>().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        any::<u32>().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        any::<u64>().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        u128_arb().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        u256_arb().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        u512_arb().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        key_arb().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        uref_arb().prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        ".*".prop_map(|x: String| CLValue::from_t(x).expect("should create CLValue")),
        option::of(any::<u64>()).prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        vec(uref_arb(), 0..100).prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        [any::<u64>(); 32].prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        result::maybe_err(key_arb(), ".*")
            .prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        btree_map(".*", u512_arb(), 0..100)
            .prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        (any::<bool>()).prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        (any::<bool>(), any::<i32>())
            .prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
        (any::<bool>(), any::<i32>(), any::<i64>())
            .prop_map(|x| CLValue::from_t(x).expect("should create CLValue")),
    ]
}

pub fn result_arb() -> impl Strategy<Value = Result<u32, u32>> {
    result::maybe_ok(any::<u32>(), any::<u32>())
}
