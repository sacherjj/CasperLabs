use core::convert::TryFrom;
use types::{URef, U512};

use engine_shared::motes::Motes;
use engine_test_support::{
    Code, SessionBuilder, SessionTransferInfo, TestContextBuilder, URefAddr, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_ACCOUNT_INITIAL_BALANCE,
};

const TRANSFER_WASM: &str = "transfer_main_purse_to_new_purse.wasm";
const NEW_PURSE_NAME: &str = "test_purse";
const FIRST_TRANSFER_AMOUNT: u64 = 142;
const SECOND_TRANSFER_AMOUNT: u64 = 250;
const ZERO_U512: U512 = U512([0; 8]);

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
    let target_purse = None;
    let transfer_amount = Motes::new(U512::try_from(FIRST_TRANSFER_AMOUNT).expect("U512 from u64"));
    let source_only_session_transfer_info = SessionTransferInfo {
        source_purse,
        target_purse,
        transfer_amount,
    };

    // Doing a transfer from main purse to create new purse and store Uref under NEW_PURSE_NAME.
    let session = SessionBuilder::new(
        Code::from(TRANSFER_WASM),
        (NEW_PURSE_NAME, transfer_amount.value()),
    )
    .with_address(DEFAULT_ACCOUNT_ADDR)
    .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
    .with_check_transfer_success(source_only_session_transfer_info)
    .build();
    test_context.run(session);

    let target_purse_value = test_context
        .query(DEFAULT_ACCOUNT_ADDR, NEW_PURSE_NAME)
        .expect("URef for NEW_PURSE_NAME");
    let target_purse = None;
    let transfer_amount =
        Motes::new(U512::try_from(SECOND_TRANSFER_AMOUNT).expect("U512 from u64"));
    let source_and_target_session_transfer_info = SessionTransferInfo {
        source_purse,
        target_purse,
        transfer_amount,
    };

    // Doing a transfer from main purse to create new purse and store Uref under NEW_PURSE_NAME.
    let session = SessionBuilder::new(
        Code::from(TRANSFER_WASM),
        (NEW_PURSE_NAME, transfer_amount.value()),
    )
    .with_address(DEFAULT_ACCOUNT_ADDR)
    .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
    .with_check_transfer_success(source_and_target_session_transfer_info)
    .build();
    test_context.run(session);
}
