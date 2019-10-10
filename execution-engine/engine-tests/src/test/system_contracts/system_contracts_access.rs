use contract_ffi::value::U512;

use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const CONTRACT_SYSTEM_CONTRACTS_ACCESS: &str = "system_contracts_access.wasm";
const CONTRACT_TRANSFER_TO_ACCOUNT_01: &str = "transfer_to_account_01.wasm";

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

lazy_static! {
    static ref ACCOUNT_1_INITIAL_BALANCE: U512 = *DEFAULT_PAYMENT * 10;
}

fn run_test_with_address(builder: &mut InMemoryWasmTestBuilder, address: [u8; 32]) {
    let exec_request =
        ExecuteRequestBuilder::standard(address, CONTRACT_SYSTEM_CONTRACTS_ACCESS, ()).build();

    builder.exec(exec_request).expect_success().commit();
}

#[ignore]
#[test]
fn should_verify_system_contracts_access_rights_default() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (ACCOUNT_1_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
    )
    .build();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit();

    run_test_with_address(&mut builder, DEFAULT_ACCOUNT_ADDR);
    run_test_with_address(&mut builder, ACCOUNT_1_ADDR);
}
