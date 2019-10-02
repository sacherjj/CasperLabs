use contract_ffi::value::U512;

use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const CONTRACT_CHECK_SYSTEM_CONTRACT_UREFS_ACCESS_RIGHTS: &str =
    "check_system_contract_urefs_access_rights";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account";
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

lazy_static! {
    static ref ACCOUNT_1_INITIAL_BALANCE: U512 = *DEFAULT_PAYMENT;
}

#[ignore]
#[test]
fn should_have_read_only_access_to_system_contract_urefs() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = {
        let contract_name = format!("{}.wasm", CONTRACT_TRANSFER_PURSE_TO_ACCOUNT);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (ACCOUNT_1_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
        )
    };

    let exec_request_2 = {
        let contract_name = format!(
            "{}.wasm",
            CONTRACT_CHECK_SYSTEM_CONTRACT_UREFS_ACCESS_RIGHTS
        );
        ExecuteRequestBuilder::standard(ACCOUNT_1_ADDR, &contract_name, ())
    };

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .commit()
        .exec_with_exec_request(exec_request_2)
        .commit()
        .expect_success();
}
