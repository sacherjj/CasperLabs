extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;
use std::convert::TryInto;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::account::{Account, PurseId};
use contract_ffi::value::contract::Contract;
use contract_ffi::value::U512;

use engine_core::engine_state::genesis::{POS_PAYMENT_PURSE, POS_REWARDS_PURSE};
use engine_core::execution::POS_NAME;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(unused)]
mod test_support;

const FINALIZE_PAYMENT: &str = "pos_finalize_payment.wasm";
const LOCAL_REFUND_PURSE: &str = "local_refund_purse";
const POS_REFUND_PURSE_NAME: &str = "pos_refund_purse";
const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const SYSTEM_ADDR: [u8; 32] = [0u8; 32];
const ACCOUNT_ADDR: [u8; 32] = [1u8; 32];

fn initialize() -> WasmTestBuilder {
    let mut builder = WasmTestBuilder::default();

    builder
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            SYSTEM_ADDR,
        )
        .expect_success()
        .commit()
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            2,
            ACCOUNT_ADDR,
        )
        .expect_success()
        .commit();

    builder
}

#[ignore]
#[test]
fn finalize_payment_should_not_be_run_by_non_system_accounts() {
    let mut builder = initialize();
    let payment_amount = U512::from(300);
    let spent_amount = U512::from(75);
    let refund_purse: Option<PurseId> = None;
    let args = (payment_amount, refund_purse, spent_amount, ACCOUNT_ADDR);

    assert!(builder
        .exec_with_args(GENESIS_ADDR, FINALIZE_PAYMENT, DEFAULT_BLOCK_TIME, 3, args)
        .is_error());
    assert!(builder
        .exec_with_args(ACCOUNT_ADDR, FINALIZE_PAYMENT, DEFAULT_BLOCK_TIME, 1, args)
        .is_error());
}

#[ignore]
#[test]
fn finalize_payment_should_pay_validators_and_refund_user() {
    let mut builder = initialize();
    let payment_amount = U512::from(300);
    let spent_amount = U512::from(75);
    let refund_purse_flag: u8 = 0;
    let args = (
        payment_amount,
        refund_purse_flag,
        spent_amount,
        ACCOUNT_ADDR,
    );

    let payment_pre_balance = get_pos_payment_purse_balance(&builder);
    let rewards_pre_balance = get_pos_rewards_purse_balance(&builder);
    let account_pre_balance = get_account_balance(&builder, ACCOUNT_ADDR);

    assert!(payment_pre_balance.is_zero()); // payment purse always starts with zero balance

    builder
        .exec_with_args(SYSTEM_ADDR, FINALIZE_PAYMENT, DEFAULT_BLOCK_TIME, 1, args)
        .expect_success()
        .commit();

    let payment_post_balance = get_pos_payment_purse_balance(&builder);
    let rewards_post_balance = get_pos_rewards_purse_balance(&builder);
    let account_post_balance = get_account_balance(&builder, ACCOUNT_ADDR);

    assert_eq!(rewards_pre_balance + spent_amount, rewards_post_balance); // validators get paid

    // user gets refund
    assert_eq!(
        account_pre_balance + payment_amount - spent_amount,
        account_post_balance
    );

    assert!(payment_post_balance.is_zero()); // payment purse always ends with zero balance
}

#[ignore]
#[test]
fn finalize_payment_should_refund_to_specified_purse() {
    let mut builder = initialize();
    let payment_amount = U512::from(300);
    let spent_amount = U512::from(75);
    let refund_purse_flag: u8 = 1;
    let args = (
        payment_amount,
        refund_purse_flag,
        spent_amount,
        ACCOUNT_ADDR,
    );

    let payment_pre_balance = get_pos_payment_purse_balance(&builder);
    let rewards_pre_balance = get_pos_rewards_purse_balance(&builder);
    let refund_pre_balance = get_named_account_balance(&builder, SYSTEM_ADDR, LOCAL_REFUND_PURSE)
        .unwrap_or_else(U512::zero);

    assert!(get_pos_refund_purse(&builder).is_none()); // refund_purse always starts unset
    assert!(payment_pre_balance.is_zero()); // payment purse always starts with zero balance

    builder
        .exec_with_args(SYSTEM_ADDR, FINALIZE_PAYMENT, DEFAULT_BLOCK_TIME, 1, args)
        .expect_success()
        .commit();

    let payment_post_balance = get_pos_payment_purse_balance(&builder);
    let rewards_post_balance = get_pos_rewards_purse_balance(&builder);
    let refund_post_balance = get_named_account_balance(&builder, SYSTEM_ADDR, LOCAL_REFUND_PURSE)
        .expect("should have refund balance");

    assert_eq!(rewards_pre_balance + spent_amount, rewards_post_balance); // validators get paid

    // user gets refund
    assert_eq!(
        refund_pre_balance + payment_amount - spent_amount,
        refund_post_balance
    );

    assert!(get_pos_refund_purse(&builder).is_none()); // refund_purse always ends unset
    assert!(payment_post_balance.is_zero()); // payment purse always ends with zero balance
}

fn get_pos_payment_purse_balance(builder: &WasmTestBuilder) -> U512 {
    let purse_id = get_pos_purse_id_by_name(builder, POS_PAYMENT_PURSE)
        .expect("should find PoS payment purse");
    get_purse_balance(builder, purse_id)
}

fn get_pos_rewards_purse_balance(builder: &WasmTestBuilder) -> U512 {
    let purse_id = get_pos_purse_id_by_name(builder, POS_REWARDS_PURSE)
        .expect("should find PoS rewards purse");
    get_purse_balance(builder, purse_id)
}

fn get_pos_refund_purse(builder: &WasmTestBuilder) -> Option<Key> {
    let pos_contract = get_pos_contract(builder);

    pos_contract
        .urefs_lookup()
        .get(POS_REFUND_PURSE_NAME)
        .cloned()
}

fn get_pos_contract(builder: &WasmTestBuilder) -> Contract {
    let genesis_key = Key::Account(GENESIS_ADDR);
    let pos_uref: Key = builder
        .query(None, genesis_key, &[POS_NAME])
        .and_then(|v| v.try_into().ok())
        .expect("should find PoS URef");

    builder
        .query(None, pos_uref, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should find PoS Contract")
}

fn get_pos_purse_id_by_name(builder: &WasmTestBuilder, purse_name: &str) -> Option<PurseId> {
    let pos_contract = get_pos_contract(builder);

    pos_contract
        .urefs_lookup()
        .get(purse_name)
        .and_then(Key::as_uref)
        .map(|u| PurseId::new(*u))
}

fn get_account_balance(builder: &WasmTestBuilder, account_address: [u8; 32]) -> U512 {
    let account_key = Key::Account(account_address);

    let account: Account = builder
        .query(None, account_key, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should find balance uref");

    let purse_id = account.purse_id();

    get_purse_balance(builder, purse_id)
}

fn get_named_account_balance(
    builder: &WasmTestBuilder,
    account_address: [u8; 32],
    name: &str,
) -> Option<U512> {
    let account_key = Key::Account(account_address);

    let account: Account = builder
        .query(None, account_key, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should find balance uref");

    let purse_id = account
        .urefs_lookup()
        .get(name)
        .and_then(Key::as_uref)
        .map(|u| PurseId::new(*u));

    purse_id.map(|id| get_purse_balance(builder, id))
}

fn get_purse_balance(builder: &WasmTestBuilder, purse_id: PurseId) -> U512 {
    let mint = builder.get_mint_contract_uref();
    let purse_bytes = purse_id
        .value()
        .addr()
        .to_bytes()
        .expect("should be able to serialize purse bytes");

    let balance_mapping_key = Key::local(mint.addr(), &purse_bytes);
    let balance_uref = builder
        .query(None, balance_mapping_key, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should find balance uref");

    builder
        .query(None, balance_uref, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should parse balance into a U512")
}
