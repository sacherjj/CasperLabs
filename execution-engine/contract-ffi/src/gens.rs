use crate::execution::Phase;
use crate::key::*;
use crate::uref::{AccessRights, URef};
use crate::value::account::{
    AccountActivity, ActionThresholds, AssociatedKeys, BlockTime, PublicKey, PurseId, Weight,
    MAX_KEYS,
};
use crate::value::*;
use alloc::collections::BTreeMap;
use alloc::string::String;
use proptest::collection::{btree_map, vec};
use proptest::prelude::*;
use proptest::{array, bits, option, result};

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

pub fn associated_keys_arb(size: usize) -> impl Strategy<Value = AssociatedKeys> {
    proptest::collection::btree_map(public_key_arb(), weight_arb(), size).prop_map(|keys| {
        let mut associated_keys = AssociatedKeys::empty();
        keys.into_iter().for_each(|(k, v)| {
            associated_keys.add_key(k, v).unwrap();
        });
        associated_keys
    })
}

pub fn action_threshold_arb() -> impl Strategy<Value = ActionThresholds> {
    Just(Default::default())
}

pub fn account_activity_arb() -> impl Strategy<Value = AccountActivity> {
    Just(AccountActivity::new(BlockTime(1), BlockTime(1000)))
}

prop_compose! {
    pub fn account_arb()(
        pub_key in u8_slice_32(),
        nonce in any::<u64>(),
        urefs in uref_map_arb(3),
        purse_id in uref_arb(),
        thresholds in action_threshold_arb(),
        account_activity in account_activity_arb(),
        mut associated_keys in associated_keys_arb(MAX_KEYS - 1),
    ) -> Account {
            let purse_id = PurseId::new(purse_id);
            associated_keys.add_key(pub_key.into(), Weight::new(1)).unwrap();
            Account::new(
                pub_key,
                nonce,
                urefs,
                purse_id,
                associated_keys.clone(),
                thresholds.clone(),
                account_activity.clone(),
            )
    }
}

pub fn contract_arb() -> impl Strategy<Value = Contract> {
    any::<u64>().prop_flat_map(move |u64arb| {
        uref_map_arb(20).prop_flat_map(move |urefs| {
            vec(any::<u8>(), 1..1000)
                .prop_map(move |body| Contract::new(body, urefs.clone(), u64arb))
        })
    })
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

pub fn value_arb() -> impl Strategy<Value = Value> {
    // If compiler brings you here it most probably means you've added a variant to
    // `Value` enum but forgot to add generator for it.
    let stub: Option<Value> = None;
    if let Some(v) = stub {
        match v {
            Value::Int32(_)
            | Value::UInt64(_)
            | Value::UInt128(_)
            | Value::UInt256(_)
            | Value::UInt512(_)
            | Value::ByteArray(_)
            | Value::String(_)
            | Value::ListString(_)
            | Value::ListInt32(_)
            | Value::Account(_)
            | Value::Contract(_)
            | Value::Key(_)
            | Value::NamedKey(_, _)
            | Value::Unit => (),
        }
    };
    prop_oneof![
        (any::<i32>().prop_map(Value::Int32)),
        (vec(any::<u8>(), 1..1000).prop_map(Value::ByteArray)),
        (vec(any::<i32>(), 1..1000).prop_map(Value::ListInt32)),
        ("\\PC*".prop_map(Value::String)),
        (vec(any::<String>(), 1..500).prop_map(Value::ListString)),
        ("\\PC*", key_arb()).prop_map(|(n, k)| Value::NamedKey(n, k)),
        key_arb().prop_map(Value::Key),
        account_arb().prop_map(Value::Account),
        contract_arb().prop_map(Value::Contract),
        u128_arb().prop_map(Value::UInt128),
        u256_arb().prop_map(Value::UInt256),
        u512_arb().prop_map(Value::UInt512),
        Just(Value::Unit),
        (any::<u64>().prop_map(Value::UInt64)),
    ]
}

pub fn result_arb() -> impl Strategy<Value = Result<u32, u32>> {
    result::maybe_ok(any::<u32>(), any::<u32>())
}
