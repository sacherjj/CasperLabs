#![no_std]
#![no_main]

extern crate alloc;

use alloc::string::String;

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{contracts::CONTRACT_INITIAL_VERSION, runtime_args, ApiError, Key, RuntimeArgs};

const ENTRY_FUNCTION_NAME: &str = "delegate";
const PURSE_NAME_ARG_NAME: &str = "purse_name";

#[repr(u16)]
enum Args {
    DoNothingContractMetadataKey = 0,
    DoNothingVersion = 1,
    PurseName = 2,
}

#[repr(u16)]
enum CustomError {
    MissingDoNothingContractMetadataKeyArg = 0,
    MissingPurseNameArg = 1,
    ConvertingKeyIntoHash = 2,
    MissingDoNothingVersion = 3,
}

#[no_mangle]
pub extern "C" fn call() {
    let contract_metadata_key: Key = runtime::get_arg(Args::DoNothingContractMetadataKey as u32)
        .unwrap_or_revert_with(ApiError::User(
            CustomError::MissingDoNothingContractMetadataKeyArg as u16,
        ))
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let contract_metadata_hash = contract_metadata_key
        .into_hash()
        .unwrap_or_revert_with(ApiError::User(CustomError::ConvertingKeyIntoHash as u16));

    let args = {
        let new_purse_name: String = runtime::get_arg(Args::PurseName as u32)
            .unwrap_or_revert_with(ApiError::User(CustomError::MissingPurseNameArg as u16))
            .unwrap_or_revert_with(ApiError::InvalidArgument);

        runtime_args! {
            PURSE_NAME_ARG_NAME => new_purse_name,
        }
    };

    // TODO implement SemVer for argument to contract and update this
    let _version_number: u32 = runtime::get_arg(Args::DoNothingVersion as u32)
        .unwrap_or_revert_with(ApiError::User(CustomError::MissingDoNothingVersion as u16))
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    // TODO version and new_purse_name come in correctly, but we are not correctly running the
    // stored contracts.
    runtime::call_versioned_contract(
        contract_metadata_hash,
        CONTRACT_INITIAL_VERSION,
        ENTRY_FUNCTION_NAME,
        args,
    )
}
