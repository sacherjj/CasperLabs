use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::{EngineConfig, MAX_PAYMENT};

use crate::support::test_support::{DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_pos_refund_purse_contract_default_account() {
    let mut builder = initialize();
    refund_tests(&mut builder, DEFAULT_ACCOUNT_ADDR);
}

#[ignore]
#[test]
fn should_run_pos_refund_purse_contract_account_1() {
    let mut builder = initialize();
    transfer(&mut builder, ACCOUNT_1_ADDR, U512::from(2 * MAX_PAYMENT));
    refund_tests(&mut builder, ACCOUNT_1_ADDR);
}

fn initialize() -> InMemoryWasmTestBuilder {
    let engine_config = EngineConfig::new().set_use_payment_code(true);
    let mut builder = InMemoryWasmTestBuilder::new(engine_config);

    builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    builder
}

fn transfer(builder: &mut InMemoryWasmTestBuilder, address: [u8; 32], amount: U512) {
    let exec_request = {
        let genesis_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);
        let account_1_public_key = PublicKey::new(address);

        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, amount),
            )
            .with_payment_code("standard_payment.wasm", (U512::from(MAX_PAYMENT),))
            .with_authorization_keys(&[genesis_public_key])
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit();
}

fn refund_tests(builder: &mut InMemoryWasmTestBuilder, address: [u8; 32]) {
    let exec_request = {
        let public_key = PublicKey::new(address);

        let deploy = DeployBuilder::new()
            .with_address(address)
            .with_deploy_hash([2; 32])
            .with_session_code("do_nothing.wasm", ())
            .with_payment_code("pos_refund_purse.wasm", (U512::from(MAX_PAYMENT),))
            .with_authorization_keys(&[public_key])
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit();
}
