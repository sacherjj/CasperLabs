use alloc::{collections::BTreeMap, string::String};

use proptest::{
    array, bits,
    collection::{btree_map, vec},
    option,
    prelude::*,
    result,
};

use crate::{
    execution::Phase,
    key::*,
    uref::{AccessRights, URef},
    value::{
        account::{PublicKey, Weight},
        CLType, CLValue, ProtocolVersion, SemVer, U128, U256, U512,
    },
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
            | CLType::Tuple4(_)
            | CLType::Tuple5(_)
            | CLType::Tuple6(_)
            | CLType::Tuple7(_)
            | CLType::Tuple8(_)
            | CLType::Tuple9(_)
            | CLType::Tuple10(_) => (),
        }
    };
    // TODO(Fraser) - complete
    prop_oneof![
        any::<bool>().prop_map(|b| CLValue::from_t(&b).expect("should create CLValue")),
//
//
//        (any::<i32>().prop_map(Value::Int32)),
//        (vec(any::<u8>(), 1..1000).prop_map(Value::ByteArray)),
//        (vec(any::<i32>(), 1..1000).prop_map(Value::ListInt32)),
//        ("\\PC*".prop_map(Value::String)),
//        (vec(any::<String>(), 1..500).prop_map(Value::ListString)),
//        ("\\PC*", key_arb()).prop_map(|(n, k)| Value::NamedKey(n, k)),
//        key_arb().prop_map(Value::Key),
//        account_arb().prop_map(Value::Account),
//        contract_arb().prop_map(Value::Contract),
//        u128_arb().prop_map(Value::UInt128),
//        u256_arb().prop_map(Value::UInt256),
//        u512_arb().prop_map(Value::UInt512),
//        Just(Value::Unit),
//        (any::<u64>().prop_map(Value::UInt64)),
    ]
}

pub fn result_arb() -> impl Strategy<Value = Result<u32, u32>> {
    result::maybe_ok(any::<u32>(), any::<u32>())
}
