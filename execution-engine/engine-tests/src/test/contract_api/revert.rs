use crate::support::test_support::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

const REVERT_WASM: &str = "revert.wasm";
const BLOCK_TIME: u64 = 42;

#[ignore]
#[test]
fn should_revert() {
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(REVERT_WASM, ())
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy)
            .with_block_time(BLOCK_TIME)
            .build()
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .commit()
        .is_error();
}
