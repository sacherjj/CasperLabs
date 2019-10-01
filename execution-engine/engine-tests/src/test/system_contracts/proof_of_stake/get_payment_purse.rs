use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

use crate::support::test_support::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = 100_000_000 + 100;

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_default_account() {
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(
                "pos_get_payment_purse.wasm",
                // Default funding amount for standard payment
                (U512::from(MAX_PAYMENT),),
            )
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_account_1() {
    let exec_request_1 = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            .with_session_code(
                "transfer_purse_to_account.wasm",
                // Default funding amount for standard payment
                (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            )
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };
    let exec_request_2 = {
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            .with_session_code("pos_get_payment_purse.wasm", (*DEFAULT_PAYMENT,))
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit();
}
