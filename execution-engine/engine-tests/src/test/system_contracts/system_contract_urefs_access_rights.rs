use lazy_static::lazy_static;

use engine_test_support::{
    internal::{
        ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_PAYMENT,
        DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{account::PublicKey, U512};

const CONTRACT_CHECK_SYSTEM_CONTRACT_UREFS_ACCESS_RIGHTS: &str =
    "check_system_contract_urefs_access_rights.wasm";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const ACCOUNT_1_ADDR: PublicKey = PublicKey::ed25519_from([1u8; 32]);

lazy_static! {
    static ref ACCOUNT_1_INITIAL_BALANCE: U512 = *DEFAULT_PAYMENT;
}

#[ignore]
#[test]
fn should_have_read_only_access_to_system_contract_urefs() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
    )
    .build();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_CHECK_SYSTEM_CONTRACT_UREFS_ACCESS_RIGHTS,
        (),
    )
    .build();

    builder
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request_1)
        .commit()
        .exec(exec_request_2)
        .commit()
        .expect_success();
}
