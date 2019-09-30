use crate::support::test_support::{
    DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use contract_ffi::value::{Account, Value};
use engine_core::engine_state::MAX_PAYMENT;

use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = MAX_PAYMENT;

#[ignore]
#[test]
fn should_run_main_purse_contract_default_account() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let default_account = if let Some(Value::Account(account)) =
        builder.query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
    {
        account
    } else {
        panic!("could not get account")
    };

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("main_purse.wasm", (default_account.purse_id(), ()))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_main_purse_contract_account_1() {
    let account_key = Key::Account(ACCOUNT_1_ADDR);

    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            )
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    let builder = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit();

    let account_1: Account = {
        let tmp = builder.clone();
        let transforms = tmp.get_transforms();
        crate::support::test_support::get_account(&transforms[0], &account_key)
            .expect("should get account")
    };

    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("main_purse.wasm", (account_1.purse_id(),))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit();
}
