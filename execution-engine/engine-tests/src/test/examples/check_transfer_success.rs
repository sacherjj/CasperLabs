use core::convert::TryFrom;
use types::U512;

use engine_shared::motes::Motes;
use engine_test_support::{
    Code, SessionBuilder, SessionTransferInfo, TestContextBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_ACCOUNT_INITIAL_BALANCE,
};

const TRANSFER_WASM: &str = "transfer_main_purse_to_new_purse.wasm";
const NEW_PURSE_NAME: &str = "test_purse";
const FIRST_TRANSFER_AMOUNT: u64 = 142;
const SECOND_TRANSFER_AMOUNT: u64 = 250;

#[ignore]
#[test]
fn test_check_transfer_success() {
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
    test_context.run(session);

    // retrieve newly created purse URef
    // TODO: Figure out how to get ew account URef
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
    test_context.run(session);
}
