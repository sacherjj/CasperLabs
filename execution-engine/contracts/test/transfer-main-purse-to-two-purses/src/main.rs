#![no_std]
#![no_main]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, URef, U512};

#[repr(u8)]
enum Args {
    DestinationPurseOne = 0,
    TransferAmountOne = 1,
    DestinationPurseTwo = 2,
    TransferAmountTwo = 3,
}

#[repr(u16)]
enum CustomError {
    TransferToPurseOneFailed = 101,
    TransferToPurseTwoFailed = 102,
}

#[no_mangle]
pub extern "C" fn call() {
    let main_purse: URef = account::get_main_purse();

    let dst_purse_one_name: String = runtime::get_arg(Args::DestinationPurseOne as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let dst_purse_one = if !runtime::has_key(&dst_purse_one_name) {
        // Create and store purse if doesn't exist
        let purse = system::create_purse();
        runtime::put_key(&dst_purse_one_name, purse.into());
        purse
    } else {
        let destination_purse_one_key =
            runtime::get_key(&dst_purse_one_name).unwrap_or_revert_with(ApiError::InvalidArgument);
        match destination_purse_one_key.as_uref() {
            Some(uref) => *uref,
            None => runtime::revert(ApiError::UnexpectedKeyVariant),
        }
    };
    let transfer_amount_one: U512 = runtime::get_arg(Args::TransferAmountOne as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let dst_purse_two_name: String = runtime::get_arg(Args::DestinationPurseTwo as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let dst_purse_two = if !runtime::has_key(&dst_purse_two_name) {
        // Create and store purse if doesn't exist
        let purse = system::create_purse();
        runtime::put_key(&dst_purse_two_name, purse.into());
        purse
    } else {
        let destination_purse_two_key =
            runtime::get_key(&dst_purse_two_name).unwrap_or_revert_with(ApiError::InvalidArgument);
        match destination_purse_two_key.as_uref() {
            Some(uref) => *uref,
            None => runtime::revert(ApiError::UnexpectedKeyVariant),
        }
    };
    let transfer_amount_two: U512 = runtime::get_arg(Args::TransferAmountTwo as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    system::transfer_from_purse_to_purse(main_purse, dst_purse_one, transfer_amount_one)
        .unwrap_or_revert_with(ApiError::User(CustomError::TransferToPurseOneFailed as u16));
    system::transfer_from_purse_to_purse(main_purse, dst_purse_two, transfer_amount_two)
        .unwrap_or_revert_with(ApiError::User(CustomError::TransferToPurseTwoFailed as u16));
}
