//! A collection of helper functions for use in contract code.

#![no_std]

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;

pub const POS_CONTRACT_NAME: &str = "pos";

/// All `Error` variants defined in this library will be less than or equal to `RESERVED_ERROR_MAX`.
pub const RESERVED_ERROR_MAX: u32 = core::u8::MAX as u32;

/// Variants to be passed to `contract_api::revert()`.  No value will exceed `RESERVED_ERROR_MAX`.
#[repr(u8)]
pub enum Error {
    GetPosURef = 1,
    PosURefToTURef = 2,
    PosNotFound = 3,
    ReadPosKeyAsContract = 4,
    PosToContractPointer = 5,
    GetPosInnerURef = 6,
    Transfer = 7,
}

/// Returns a URef pointer to the PoS contract.
pub fn get_pos_contract() -> ContractPointer {
    let pos_uref = contract_api::get_uref(POS_CONTRACT_NAME)
        .unwrap_or_else(|| contract_api::revert(Error::GetPosURef as u32));
    let pos_turef = pos_uref
        .to_turef()
        .unwrap_or_else(|| contract_api::revert(Error::PosURefToTURef as u32));
    let maybe_pos_pointer = match contract_api::read::<Key>(pos_turef) {
        Ok(Some(pos_key)) => pos_key.to_c_ptr(),
        Ok(None) => contract_api::revert(Error::PosNotFound as u32),
        Err(_) => contract_api::revert(Error::ReadPosKeyAsContract as u32),
    };
    maybe_pos_pointer.unwrap_or_else(|| contract_api::revert(Error::PosToContractPointer as u32))
}

/// Returns a URef pointer to the PoS contract with READ access only.
pub fn get_pos_contract_read_only() -> ContractPointer {
    let contract_ptr = get_pos_contract();
    if let ContractPointer::URef(inner) = contract_ptr {
        ContractPointer::URef(TURef::new(inner.addr(), AccessRights::READ))
    } else {
        contract_api::revert(Error::GetPosInnerURef as u32)
    }
}
