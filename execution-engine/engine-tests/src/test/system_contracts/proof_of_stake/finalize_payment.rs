use std::convert::TryInto;

use engine_core::engine_state::{
    genesis::{POS_PAYMENT_PURSE, POS_REWARDS_PURSE},
    CONV_RATE,
};
use engine_shared::{account::Account, motes::Motes};
use engine_test_support::low_level::{
    utils, DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT,
};
use types::{
    account::{PublicKey, PurseId},
    Key, U512,
};

const CONTRACT_FINALIZE_PAYMENT: &str = "pos_finalize_payment.wasm";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const FINALIZE_PAYMENT: &str = "pos_finalize_payment.wasm";
const LOCAL_REFUND_PURSE: &str = "local_refund_purse";
const POS_REFUND_PURSE_NAME: &str = "pos_refund_purse";

const SYSTEM_ADDR: [u8; 32] = [0u8; 32];
const ACCOUNT_ADDR: [u8; 32] = [1u8; 32];

fn initialize() -> InMemoryWasmTestBuilder {
    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (SYSTEM_ADDR, *DEFAULT_PAYMENT),
    )
    .build();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_ADDR, *DEFAULT_PAYMENT),
    )
    .build();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
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
    let args = (
        payment_amount,
        refund_purse,
        Some(spent_amount),
        Some(ACCOUNT_ADDR),
    );

    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_FINALIZE_PAYMENT, args)
            .build();
    let exec_request_2 =
        ExecuteRequestBuilder::standard(ACCOUNT_ADDR, CONTRACT_FINALIZE_PAYMENT, args).build();

    assert!(builder.exec(exec_request_1).is_error());

    assert!(builder.exec(exec_request_2).is_error());
}

#[ignore]
#[test]
fn finalize_payment_should_refund_to_specified_purse() {
    let mut builder = InMemoryWasmTestBuilder::default();
    let payment_amount = *DEFAULT_PAYMENT;
    let refund_purse_flag: u8 = 1;
    // Don't need to run finalize_payment manually, it happens during
    // the deploy because payment code is enabled.
    let args: (U512, u8, Option<U512>, Option<PublicKey>) =
        (payment_amount, refund_purse_flag, None, None);

    builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let payment_pre_balance = get_pos_payment_purse_balance(&builder);
    let rewards_pre_balance = get_pos_rewards_purse_balance(&builder);
    let refund_pre_balance =
        get_named_account_balance(&builder, DEFAULT_ACCOUNT_ADDR, LOCAL_REFUND_PURSE)
            .unwrap_or_else(U512::zero);

    assert!(
        get_pos_refund_purse(&builder).is_none(),
        "refund_purse should start unset"
    );
    assert!(
        payment_pre_balance.is_zero(),
        "payment purse should start with zero balance"
    );

    let exec_request = {
        let genesis_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code("do_nothing.wasm", ())
            .with_payment_code(FINALIZE_PAYMENT, args)
            .with_authorization_keys(&[genesis_public_key])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };
    builder.exec(exec_request).expect_success().commit();

    let spent_amount: U512 = {
        let response = builder
            .get_exec_response(0)
            .expect("there should be a response");

        let success_result = utils::get_success_result(response);
        Motes::from_gas(success_result.cost(), CONV_RATE)
            .expect("should have motes")
            .value()
    };

    let payment_post_balance = get_pos_payment_purse_balance(&builder);
    let rewards_post_balance = get_pos_rewards_purse_balance(&builder);
    let refund_post_balance =
        get_named_account_balance(&builder, DEFAULT_ACCOUNT_ADDR, LOCAL_REFUND_PURSE)
            .expect("should have refund balance");
    let expected_amount = rewards_pre_balance + spent_amount;
    assert_eq!(
        expected_amount, rewards_post_balance,
        "validators should get paid; expected: {}, actual: {}",
        expected_amount, rewards_post_balance
    );

    // user gets refund
    assert_eq!(
        refund_pre_balance + payment_amount - spent_amount,
        refund_post_balance,
        "user should get refund"
    );

    assert!(
        get_pos_refund_purse(&builder).is_none(),
        "refund_purse always ends unset"
    );
    assert!(
        payment_post_balance.is_zero(),
        "payment purse should ends with zero balance"
    );
}

// ------------- utility functions -------------------- //

fn get_pos_payment_purse_balance(builder: &InMemoryWasmTestBuilder) -> U512 {
    let purse_id = get_pos_purse_id_by_name(builder, POS_PAYMENT_PURSE)
        .expect("should find PoS payment purse");
    builder.get_purse_balance(purse_id)
}

fn get_pos_rewards_purse_balance(builder: &InMemoryWasmTestBuilder) -> U512 {
    let purse_id = get_pos_purse_id_by_name(builder, POS_REWARDS_PURSE)
        .expect("should find PoS rewards purse");
    builder.get_purse_balance(purse_id)
}

fn get_pos_refund_purse(builder: &InMemoryWasmTestBuilder) -> Option<Key> {
    let pos_contract = builder.get_pos_contract();

    pos_contract
        .named_keys()
        .get(POS_REFUND_PURSE_NAME)
        .cloned()
}

fn get_pos_purse_id_by_name(
    builder: &InMemoryWasmTestBuilder,
    purse_name: &str,
) -> Option<PurseId> {
    let pos_contract = builder.get_pos_contract();

    pos_contract
        .named_keys()
        .get(purse_name)
        .and_then(Key::as_uref)
        .map(|u| PurseId::new(*u))
}

fn get_named_account_balance(
    builder: &InMemoryWasmTestBuilder,
    account_address: [u8; 32],
    name: &str,
) -> Option<U512> {
    let account_key = Key::Account(account_address);

    let account: Account = builder
        .query(None, account_key, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should find balance uref");

    let purse_id = account
        .named_keys()
        .get(name)
        .and_then(Key::as_uref)
        .map(|u| PurseId::new(*u));

    purse_id.map(|id| builder.get_purse_balance(id))
}
