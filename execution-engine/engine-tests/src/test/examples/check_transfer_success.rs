use core::convert::TryFrom;
use types::U512;

use engine_shared::motes::Motes;
use engine_test_support::{
    Code, SessionBuilder, SessionTransferInfo, TestContextBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_ACCOUNT_INITIAL_BALANCE,
};

const TRANSFER_WASM: &str = "transfer_main_purse_to_new_purse.wasm";
const TRANSFER_TO_TWO_PURSES: &str = "transfer_main_purse_to_two_purses.wasm";
const NEW_PURSE_NAME: &str = "test_purse";
const SECOND_PURSE_NAME: &str = "second_purse";
const FIRST_TRANSFER_AMOUNT: u64 = 142;
const SECOND_TRANSFER_AMOUNT: u64 = 250;

#[ignore]
#[test]
fn test_check_transfer_success_with_source_only() {
    let mut test_context = TestContextBuilder::new()
        .with_account(
            DEFAULT_ACCOUNT_ADDR,
            U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE),
        )
        .build();

    // Getting main purse Uref to verify transfer
    let source_purse = test_context
        .get_main_purse_address(DEFAULT_ACCOUNT_ADDR)
        .expect("main purse address");
    // Target purse doesn't exist yet, so only verifying removal from source
    let maybe_target_purse = None;
    let transfer_amount = Motes::new(U512::try_from(FIRST_TRANSFER_AMOUNT).expect("U512 from u64"));
    let source_only_session_transfer_info =
        SessionTransferInfo::new(source_purse, maybe_target_purse, transfer_amount);

    // Doing a transfer from main purse to create new purse and store Uref under NEW_PURSE_NAME.
    let session_code = Code::from(TRANSFER_WASM);
    let session_args = (NEW_PURSE_NAME, transfer_amount.value());
    let session = SessionBuilder::new(session_code, session_args)
        .with_address(DEFAULT_ACCOUNT_ADDR)
        .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
        .with_check_transfer_success(source_only_session_transfer_info)
        .build();
    test_context
        .run(session)
        .expect("test_context successful run");
}

#[ignore]
#[test]
fn test_check_transfer_success_with_source_only_errors() {
    let mut test_context = TestContextBuilder::new()
        .with_account(
            DEFAULT_ACCOUNT_ADDR,
            U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE),
        )
        .build();

    // Getting main purse Uref to verify transfer
    let source_purse = test_context
        .get_main_purse_address(DEFAULT_ACCOUNT_ADDR)
        .expect("main purse address");
    let maybe_target_purse = None;
    // Setup mismatch between transfer_amount performed and given to trigger assertion.
    let transfer_amount = Motes::new(U512::try_from(FIRST_TRANSFER_AMOUNT).expect("U512 from u64"));
    let wrong_transfer_amount =
        transfer_amount - Motes::new(U512::try_from(1u64).expect("U512 from 64"));
    let source_only_session_transfer_info =
        SessionTransferInfo::new(source_purse, maybe_target_purse, transfer_amount);

    // Doing a transfer from main purse to create new purse and store Uref under NEW_PURSE_NAME.
    let session_code = Code::from(TRANSFER_WASM);
    let session_args = (NEW_PURSE_NAME, wrong_transfer_amount.value());
    // Handle expected assertion fail.
    let session = SessionBuilder::new(session_code, session_args)
        .with_address(DEFAULT_ACCOUNT_ADDR)
        .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
        .with_check_transfer_success(source_only_session_transfer_info)
        .build();
    let result = test_context.run(session);
    assert!(result.is_err());
}

#[ignore]
#[test]
fn test_check_transfer_success_with_source_and_target() {
    let mut test_context = TestContextBuilder::new()
        .with_account(
            DEFAULT_ACCOUNT_ADDR,
            U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE),
        )
        .build();

    // Getting main purse Uref to verify transfer
    let source_purse = test_context
        .get_main_purse_address(DEFAULT_ACCOUNT_ADDR)
        .expect("main purse address");
    // retrieve newly created purse URef
    // TODO: Figure out how to get new account URef
    // let target_purse_value: Result<Vec<u8>, Err> = test_context
    //     .query(DEFAULT_ACCOUNT_ADDR, &[NEW_PURSE_NAME])
    //     .unwrap_or_else(|_| panic!("{} purse not found", NEW_PURSE_NAME))
    //     .into_t();
    // .unwrap_or_else(|_| panic!("Unable to parse {}", NEW_PURSE_NAME));
    let maybe_target_purse = None;
    let transfer_amount =
        Motes::new(U512::try_from(SECOND_TRANSFER_AMOUNT).expect("U512 from u64"));
    let source_and_target_session_transfer_info =
        SessionTransferInfo::new(source_purse, maybe_target_purse, transfer_amount);

    // Doing a transfer from main purse to create new purse and store Uref under NEW_PURSE_NAME.
    let session_code = Code::from(TRANSFER_WASM);
    let session_args = (NEW_PURSE_NAME, transfer_amount.value());
    let session = SessionBuilder::new(session_code, session_args)
        .with_address(DEFAULT_ACCOUNT_ADDR)
        .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
        .with_check_transfer_success(source_and_target_session_transfer_info)
        .build();
    test_context
        .run(session)
        .expect("test_context successful run");
}

#[ignore]
#[test]
fn test_check_transfer_success_with_source_and_target_errors() {
    let mut test_context = TestContextBuilder::new()
        .with_account(
            DEFAULT_ACCOUNT_ADDR,
            U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE),
        )
        .build();

    // Getting main purse Uref to verify transfer
    let source_purse = test_context
        .get_main_purse_address(DEFAULT_ACCOUNT_ADDR)
        .expect("main purse address");
    let maybe_target_purse = None;

    // Contract will transfer from main purse twice, into two different purses
    // This call will create the purses, so we can get the URef to destination purses.
    let transfer_one_amount =
        Motes::new(U512::try_from(FIRST_TRANSFER_AMOUNT).expect("U512 from u64"));
    let transfer_two_amount =
        Motes::new(U512::try_from(SECOND_TRANSFER_AMOUNT).expect("U512 from u64"));
    let main_purse_transfer_from_amount = transfer_one_amount + transfer_two_amount;
    let source_only_session_transfer_info = SessionTransferInfo::new(
        source_purse,
        maybe_target_purse,
        main_purse_transfer_from_amount,
    );

    // Will create two purses NEW_PURSE_NAME and SECOND_PURSE_NAME
    let session_code = Code::from(TRANSFER_TO_TWO_PURSES);
    let session_args = (
        NEW_PURSE_NAME,
        transfer_one_amount.value(),
        SECOND_PURSE_NAME,
        transfer_two_amount.value(),
    );
    let session = SessionBuilder::new(session_code, session_args)
        .with_address(DEFAULT_ACCOUNT_ADDR)
        .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
        .with_check_transfer_success(source_only_session_transfer_info)
        .build();
    test_context
        .run(session)
        .expect("test_context successful run");

    // retrieve newly created purse URef
    // TODO: Figure out how to get new account URef
    // let target_purse_value: Result<Vec<u8>, Err> = test_context
    //     .query(DEFAULT_ACCOUNT_ADDR, &[NEW_PURSE_NAME])
    //     .unwrap_or_else(|_| panic!("{} purse not found", NEW_PURSE_NAME))
    //     .into_t();
    // .unwrap_or_else(|_| panic!("Unable to parse {}", NEW_PURSE_NAME));
    let maybe_target_purse = None; // TODO: Put valid URef here
    let source_and_target_session_transfer_info = SessionTransferInfo::new(
        source_purse,
        maybe_target_purse,
        main_purse_transfer_from_amount,
    );

    // Same transfer as before, but with maybe_target_purse active for validating amount into purse
    // The test for total pulled from main purse should not assert.
    // The test for total into NEW_PURSE_NAME is only part of transfer and should assert.
    let session_code = Code::from(TRANSFER_TO_TWO_PURSES);
    let session_args = (
        NEW_PURSE_NAME,
        transfer_one_amount.value(),
        SECOND_PURSE_NAME,
        transfer_two_amount.value(),
    );
    let session = SessionBuilder::new(session_code, session_args)
        .with_address(DEFAULT_ACCOUNT_ADDR)
        .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
        .with_check_transfer_success(source_and_target_session_transfer_info)
        .build();
    let result = test_context.run(session);
    assert!(result.is_err());
}
