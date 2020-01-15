#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{account, runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{PublicKey, PurseId},
    ApiError, Key, U512,
};

const DONATION_AMOUNT: u64 = 1;
// Different name just to make sure any routine that deals with named keys coming from different
// sources wouldn't overlap (if ever that's possible)
const DONATION_BOX_COPY: &str = "donation_box_copy";
const DONATION_BOX: &str = "donation_box";
const GET_MAIN_PURSE: &str = "get_main_purse";
const MAINTAINER: &str = "maintainer";
const METHOD_CALL: &str = "call";
const METHOD_INSTALL: &str = "install";
const TRANSFER_FROM_PURSE_TO_ACCOUNT: &str = "transfer_from_purse_to_account";
const TRANSFER_FROM_PURSE_TO_PURSE: &str = "transfer_from_purse_to_purse";
const TRANSFER_FUNDS_EXT: &str = "transfer_funds_ext";
const TRANSFER_FUNDS_KEY: &str = "transfer_funds";
const TRANSFER_TO_ACCOUNT: &str = "transfer_to_account";

enum DelegateArg {
    Method = 0,
    ContractKey = 1,
    SubContractMethodFwd = 2,
}

enum TransferFunds {
    Method = 0,
}

#[repr(u16)]
enum ContractError {
    InvalidTransferFundsMethod = 0,
    InvalidDelegateMethod = 1,
}

impl Into<ApiError> for ContractError {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

fn get_maintainer_public_key() -> Result<PublicKey, ApiError> {
    // Obtain maintainer address from the contract's named keys
    let maintainer_key = runtime::get_key(MAINTAINER).ok_or(ApiError::GetKey)?;
    maintainer_key
        .as_account()
        .ok_or(ApiError::UnexpectedKeyVariant)
        .map(PublicKey::new)
}

fn get_donation_box_purse() -> Result<PurseId, ApiError> {
    let donation_box_key = runtime::get_key(DONATION_BOX).ok_or(ApiError::GetKey)?;
    donation_box_key
        .as_uref()
        .cloned()
        .ok_or(ApiError::UnexpectedKeyVariant)
        .map(PurseId::new)
}

/// This method is possibly ran from a context of a different user than the initial deployer
fn transfer_funds() -> Result<(), ApiError> {
    let method: String = runtime::get_arg(TransferFunds::Method as u32)
        .ok_or(ApiError::MissingArgument)?
        .map_err(|_| ApiError::InvalidArgument)?;

    // Donation box is the purse funds will be transferred into
    let donation_box_purse = get_donation_box_purse()?;
    // This is the address of account which installed the contract
    let maintainer_public_key = get_maintainer_public_key()?;

    match method.as_str() {
        TRANSFER_FROM_PURSE_TO_PURSE => {
            let main_purse = account::get_main_purse();

            system::transfer_from_purse_to_purse(
                main_purse,
                donation_box_purse,
                U512::from(DONATION_AMOUNT),
            )?
        }
        TRANSFER_FROM_PURSE_TO_ACCOUNT => {
            let main_purse = account::get_main_purse();

            system::transfer_from_purse_to_account(
                main_purse,
                maintainer_public_key,
                U512::from(DONATION_AMOUNT),
            )?;
        }
        TRANSFER_TO_ACCOUNT => {
            system::transfer_to_account(maintainer_public_key, U512::from(DONATION_AMOUNT))?;
        }
        GET_MAIN_PURSE => {
            let _main_purse = account::get_main_purse();
        }
        _ => return Err(ContractError::InvalidTransferFundsMethod.into()),
    }

    Ok(())
}

#[no_mangle]
fn transfer_funds_ext() {
    transfer_funds().unwrap_or_revert();
}

/// Registers a function and saves it in callers named keys
fn delegate() -> Result<(), ApiError> {
    let method: String = runtime::get_arg(DelegateArg::Method as u32)
        .ok_or(ApiError::MissingArgument)?
        .map_err(|_| ApiError::InvalidArgument)?;
    match method.as_str() {
        METHOD_INSTALL => {
            // Create a purse that should be known to the contract regardless of the
            // calling context still owned by the account that deploys the contract
            let donation_box = system::create_purse();
            let maintainer = runtime::get_caller();
            // Keys below will make it possible to use within the called contract
            let known_keys = {
                let mut keys = BTreeMap::new();
                // "donation_box" is the purse owner of the contract can transfer funds from callers
                keys.insert(DONATION_BOX.into(), donation_box.value().into());
                // "maintainer" is the person who installed this contract
                keys.insert(MAINTAINER.into(), Key::Account(maintainer.value()));
                keys
            };
            // Install the contract with associated owner-related keys
            let contract_ref = storage::store_function_at_hash(TRANSFER_FUNDS_EXT, known_keys);
            runtime::put_key(TRANSFER_FUNDS_KEY, contract_ref.into());
            // For easy access in outside world here `donation_box` purse is also attached
            // to the account
            runtime::put_key(DONATION_BOX_COPY, donation_box.value().into());
        }
        METHOD_CALL => {
            // This comes from outside i.e. after deploying the contract, this key is queried, and
            // then passed into the call
            let contract_key: Key = runtime::get_arg(DelegateArg::ContractKey as u32)
                .ok_or(ApiError::MissingArgument)?
                .map_err(|_| ApiError::InvalidArgument)?;
            let contract_ref = contract_key
                .to_contract_ref()
                .ok_or(ApiError::UnexpectedKeyVariant)?;
            // This is a method that's gets forwarded into the sub contract
            let subcontract_method: String =
                runtime::get_arg(DelegateArg::SubContractMethodFwd as u32)
                    .ok_or(ApiError::MissingArgument)?
                    .map_err(|_| ApiError::InvalidArgument)?;

            let subcontract_args = (subcontract_method,);
            runtime::call_contract::<_, ()>(contract_ref, subcontract_args);
        }
        _ => return Err(ContractError::InvalidDelegateMethod.into()),
    }
    Ok(())
}

#[no_mangle]
pub extern "C" fn call() {
    delegate().unwrap_or_revert();
}
