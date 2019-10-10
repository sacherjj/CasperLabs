#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::PublicKey;

#[repr(u16)]
enum Error {
    NonEmptyNamedKeys = 100,
    MintContractIsNotURef = 101,
    PosContractIsNotURef = 102,
    InvalidMintAccessRights = 103,
    MintHasNoAccessRights = 104,
    InvalidPosAccessRights = 105,
    PosHasNoAccessRights = 106,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

const SYSTEM_ADDR: [u8; 32] = [0; 32];

/// Extracts URef from ContractPointer for the purpose of further validation
fn extract_uref_from_contract_pointer(contract_pointer: ContractPointer) -> Option<URef> {
    match contract_pointer.into_turef() {
        Some(turef) => Some(URef::new(turef.addr(), turef.access_rights())),
        None => None,
    }
}

fn delegate() {
    // Regardless of the context none of pos/mint contracts should be present

    // Step 1 - Named keys should be empty regardless of the context (system/genesis/user)
    let named_keys = contract_api::list_named_keys();
    if !named_keys.is_empty() {
        contract_api::revert(Error::NonEmptyNamedKeys);
    }

    // Step 2 - Mint and PoS should be URefs and they should have valid access rights
    let mint_contract = contract_api::get_mint();

    let expected_access_rights = if contract_api::get_caller() == PublicKey::new(SYSTEM_ADDR) {
        // System account receives read/add/write access
        AccessRights::READ_ADD_WRITE
    } else {
        // User receives read only
        AccessRights::READ
    };

    let pos_contract = contract_api::get_pos();

    let mint_uref = extract_uref_from_contract_pointer(mint_contract)
        .unwrap_or_else(|| contract_api::revert(Error::MintContractIsNotURef));
    match mint_uref.access_rights() {
        Some(access_rights) if access_rights != expected_access_rights => {
            contract_api::revert(Error::InvalidMintAccessRights)
        }
        Some(_) => {}
        None => contract_api::revert(Error::MintHasNoAccessRights),
    }

    let pos_uref = extract_uref_from_contract_pointer(pos_contract)
        .unwrap_or_else(|| contract_api::revert(Error::PosContractIsNotURef));
    match pos_uref.access_rights() {
        Some(access_rights) if access_rights != expected_access_rights => {
            contract_api::revert(Error::InvalidPosAccessRights)
        }
        Some(_) => {}
        None => contract_api::revert(Error::PosHasNoAccessRights),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    delegate();
}
