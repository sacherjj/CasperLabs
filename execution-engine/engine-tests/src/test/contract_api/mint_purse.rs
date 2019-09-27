use crate::support::test_support::{
    DeployBuilder, ExecRequestBuilder, WasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;
const SYSTEM_ADDR: [u8; 32] = [0u8; 32];

#[ignore]
#[test]
fn should_run_mint_purse_contract() {
    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("transfer_to_account_01.wasm", (SYSTEM_ADDR,))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(SYSTEM_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("mint_purse.wasm", ())
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(SYSTEM_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .commit()
        .expect_success()
        .exec_with_exec_request(exec_request_2)
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_not_allow_non_system_accounts_to_mint() {
    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("mint_purse.wasm", ())
            .with_deploy_hash([3u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    assert!(WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .commit()
        .is_error());
}
