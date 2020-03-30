//! Functions for interacting with the system contracts.

use alloc::vec::Vec;
use core::mem::MaybeUninit;

use casperlabs_types::{
    account::PublicKey, api_error, bytesrepr, ApiError, ContractRef, SystemContractType,
    TransferResult, TransferredTo, URef, U512, UREF_SERIALIZED_LENGTH,
};

use crate::{
    contract_api::{self, runtime},
    ext_ffi,
    unwrap_or_revert::UnwrapOrRevert,
};

/// Name of the reference to the Mint contract in the named keys.
pub const MINT_NAME: &str = "mint";
/// Name of the reference to the Proof of Stake contract in the named keys.
pub const POS_NAME: &str = "pos";

fn get_system_contract(system_contract: SystemContractType) -> ContractRef {
    let system_contract_index = system_contract.into();
    let uref: URef = {
        let result = {
            let mut uref_data_raw = [0u8; UREF_SERIALIZED_LENGTH];
            let value = unsafe {
                ext_ffi::get_system_contract(
                    system_contract_index,
                    uref_data_raw.as_mut_ptr(),
                    uref_data_raw.len(),
                )
            };
            api_error::result_from(value).map(|_| uref_data_raw)
        };
        // Revert for any possible error that happened on host side
        let uref_bytes = result.unwrap_or_else(|e| runtime::revert(e));
        // Deserializes a valid URef passed from the host side
        bytesrepr::deserialize(uref_bytes.to_vec()).unwrap_or_revert()
    };
    if uref.access_rights().is_none() {
        runtime::revert(ApiError::NoAccessRights);
    }
    ContractRef::URef(uref)
}

/// Returns a read-only pointer to the Mint contract.
///
/// Any failure will trigger [`revert`](runtime::revert) with an appropriate [`ApiError`].
pub fn get_mint() -> ContractRef {
    get_system_contract(SystemContractType::Mint)
}

/// Returns a read-only pointer to the Proof of Stake contract.
///
/// Any failure will trigger [`revert`](runtime::revert) with an appropriate [`ApiError`].
pub fn get_proof_of_stake() -> ContractRef {
    get_system_contract(SystemContractType::ProofOfStake)
}

/// Returns a read-only pointer to the Standard Payment contract.
///
/// Any failure will trigger [`revert`](runtime::revert) with an appropriate [`ApiError`].
pub fn get_standard_payment() -> ContractRef {
    get_system_contract(SystemContractType::StandardPayment)
}

/// Creates a new empty purse and returns its [`URef`].
pub fn create_purse() -> URef {
    let purse = contract_api::alloc_bytes(UREF_SERIALIZED_LENGTH);
    unsafe {
        let ret = ext_ffi::create_purse(purse.as_ptr(), UREF_SERIALIZED_LENGTH);
        if ret == 0 {
            let bytes = Vec::from_raw_parts(
                purse.as_ptr(),
                UREF_SERIALIZED_LENGTH,
                UREF_SERIALIZED_LENGTH,
            );
            bytesrepr::deserialize(bytes).unwrap_or_revert()
        } else {
            runtime::revert(ApiError::PurseNotCreated)
        }
    }
}

/// Returns the balance in motes of the given purse.
pub fn get_balance(purse: URef) -> Option<U512> {
    let (purse_ptr, purse_size, _bytes) = contract_api::to_ptr(purse);

    let value_size = {
        let mut output_size = MaybeUninit::uninit();
        let ret = unsafe { ext_ffi::get_balance(purse_ptr, purse_size, output_size.as_mut_ptr()) };
        match api_error::result_from(ret) {
            Ok(_) => unsafe { output_size.assume_init() },
            Err(ApiError::InvalidPurse) => return None,
            Err(error) => runtime::revert(error),
        }
    };
    let value_bytes = runtime::read_host_buffer(value_size).unwrap_or_revert();
    let value: U512 = bytesrepr::deserialize(value_bytes).unwrap_or_revert();
    Some(value)
}

/// Transfers `amount` of motes from the default purse of the account to `target`
/// account.  If `target` does not exist it will be created.
pub fn transfer_to_account(target: PublicKey, amount: U512) -> TransferResult {
    let (target_ptr, target_size, _bytes1) = contract_api::to_ptr(target);
    let (amount_ptr, amount_size, _bytes2) = contract_api::to_ptr(amount);
    let return_code =
        unsafe { ext_ffi::transfer_to_account(target_ptr, target_size, amount_ptr, amount_size) };
    TransferredTo::result_from(return_code)
}

/// Transfers `amount` of motes from `source` purse to `target` account.  If `target` does not exist
/// it will be created.
pub fn transfer_from_purse_to_account(
    source: URef,
    target: PublicKey,
    amount: U512,
) -> TransferResult {
    let (source_ptr, source_size, _bytes1) = contract_api::to_ptr(source);
    let (target_ptr, target_size, _bytes2) = contract_api::to_ptr(target);
    let (amount_ptr, amount_size, _bytes3) = contract_api::to_ptr(amount);
    let return_code = unsafe {
        ext_ffi::transfer_from_purse_to_account(
            source_ptr,
            source_size,
            target_ptr,
            target_size,
            amount_ptr,
            amount_size,
        )
    };
    TransferredTo::result_from(return_code)
}

/// Transfers `amount` of motes from `source` purse to `target` purse.  If `target` does not exist
/// the transfer fails.
pub fn transfer_from_purse_to_purse(
    source: URef,
    target: URef,
    amount: U512,
) -> Result<(), ApiError> {
    let (source_ptr, source_size, _bytes1) = contract_api::to_ptr(source);
    let (target_ptr, target_size, _bytes2) = contract_api::to_ptr(target);
    let (amount_ptr, amount_size, _bytes3) = contract_api::to_ptr(amount);
    let result = unsafe {
        ext_ffi::transfer_from_purse_to_purse(
            source_ptr,
            source_size,
            target_ptr,
            target_size,
            amount_ptr,
            amount_size,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(ApiError::Transfer)
    }
}
