use lazy_static::lazy_static;

use engine_core::execution;
use engine_shared::transform::TypeMismatch;
use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
    DEFAULT_PAYMENT,
};
use types::{URef, U512};

const CONTRACT_SYSTEM_CONTRACTS_ACCESS: &str = "system_contracts_access.wasm";
const CONTRACT_OVERWRITE_UREF_CONTENT: &str = "overwrite_uref_content.wasm";
const CONTRACT_TRANSFER_TO_ACCOUNT_01: &str = "transfer_to_account_01.wasm";

const SYSTEM_ADDR: [u8; 32] = [0u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

lazy_static! {
    static ref ACCOUNT_1_INITIAL_BALANCE: U512 = *DEFAULT_PAYMENT * 10;
    static ref SYSTEM_INITIAL_BALANCE: U512 = *DEFAULT_PAYMENT * 10;
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

fn overwrite_as_account(builder: &mut InMemoryWasmTestBuilder, uref: URef, address: [u8; 32]) {
    let exec_request =
        ExecuteRequestBuilder::standard(address, CONTRACT_OVERWRITE_UREF_CONTENT, (uref,)).build();

    let result = builder.exec(exec_request).commit().finish();

    let error_msg = result
        .builder()
        .exec_error_message(0)
        .expect("should execute with error");

    let err = format!(
        "{}",
        execution::Error::ForgedReference(uref.into_read_add_write())
    );
    assert!(error_msg.contains(&err), "error_msg: {:?}", error_msg);
}

#[ignore]
#[test]
fn should_not_overwrite_system_contract_uref_as_user() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (ACCOUNT_1_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
    )
    .build();

    builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let mint_uref = builder.get_pos_contract_uref().into_read();
    let pos_uref = builder.get_pos_contract_uref().into_read();

    let result = builder
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .finish();

    let mut builder = InMemoryWasmTestBuilder::from_result(result);

    // Try to break system contracts as user created through transfer process
    overwrite_as_account(&mut builder, mint_uref, ACCOUNT_1_ADDR);
    overwrite_as_account(&mut builder, pos_uref, ACCOUNT_1_ADDR);
    // Try to break system contracts as user created through genesis process
    overwrite_as_account(&mut builder, mint_uref, DEFAULT_ACCOUNT_ADDR);
    overwrite_as_account(&mut builder, pos_uref, DEFAULT_ACCOUNT_ADDR);
}

#[ignore]
#[test]
fn should_overwrite_system_contract_uref_as_system() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (SYSTEM_ADDR, *SYSTEM_INITIAL_BALANCE),
    )
    .build();

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .finish();

    let mint_uref = result.builder().get_mint_contract_uref();
    let pos_uref = result.builder().get_pos_contract_uref();

    let exec_request_2 =
        ExecuteRequestBuilder::standard(SYSTEM_ADDR, CONTRACT_OVERWRITE_UREF_CONTENT, (mint_uref,))
            .build();

    let mut new_builder = InMemoryWasmTestBuilder::from_result(result);

    let result_mint = new_builder.clone().exec(exec_request_2).commit().finish();

    let error_msg = result_mint
        .builder()
        .exec_error_message(0)
        .expect("should execute mint overwrite with error");
    assert!(
        error_msg.contains("FinalizationError"),
        "Expected FinalizationError, got {}",
        error_msg
    );

    let exec_request_3 =
        ExecuteRequestBuilder::standard(SYSTEM_ADDR, CONTRACT_OVERWRITE_UREF_CONTENT, (pos_uref,))
            .build();

    let result_pos = new_builder.exec(exec_request_3).commit().finish();

    let error_msg = result_pos
        .builder()
        .exec_error_message(0)
        .expect("should execute pos overwrite with error");

    let type_mismatch = TypeMismatch::new("Contract".to_string(), "String".to_string());
    let expected_error = execution::Error::TypeMismatch(type_mismatch);
    assert!(error_msg.contains(&expected_error.to_string()));
}
