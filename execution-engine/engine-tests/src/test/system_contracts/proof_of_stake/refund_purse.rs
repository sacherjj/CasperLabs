use std::collections::HashMap;

use crate::support::test_support::{DeployBuilder, ExecRequestBuilder, WasmTestBuilder};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::{EngineConfig, MAX_PAYMENT};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_pos_refund_purse_contract_genesis_account() {
    let mut builder = initialize();
    refund_tests(&mut builder, GENESIS_ADDR);
}

#[ignore]
#[test]
fn should_run_pos_refund_purse_contract_account_1() {
    let mut builder = initialize();
    transfer(&mut builder, ACCOUNT_1_ADDR, U512::from(2 * MAX_PAYMENT));
    refund_tests(&mut builder, ACCOUNT_1_ADDR);
}

fn initialize() -> WasmTestBuilder {
    let engine_config = EngineConfig::new().set_use_payment_code(true);
    let mut builder = WasmTestBuilder::new(engine_config);

    builder.run_genesis(GENESIS_ADDR, HashMap::default());

    builder
}

fn transfer(builder: &mut WasmTestBuilder, address: [u8; 32], amount: U512) {
    let exec_request = {
        let genesis_public_key = PublicKey::new(GENESIS_ADDR);
        let account_1_public_key = PublicKey::new(address);

        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, amount),
            )
            .with_payment_code("standard_payment.wasm", (U512::from(MAX_PAYMENT),))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit();
}

fn refund_tests(builder: &mut WasmTestBuilder, address: [u8; 32]) {
    let exec_request = {
        let public_key = PublicKey::new(address);

        let deploy = DeployBuilder::new()
            .with_address(address)
            .with_session_code("do_nothing.wasm", ())
            .with_payment_code("pos_refund_purse.wasm", (U512::from(MAX_PAYMENT),))
            .with_authorization_keys(&[public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit();
}
