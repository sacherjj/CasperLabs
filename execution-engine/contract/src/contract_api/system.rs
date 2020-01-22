use alloc::vec::Vec;
use core::mem::MaybeUninit;

use casperlabs_types::{
    account::{PublicKey, PurseId, PURSE_ID_SERIALIZED_LENGTH},
    api_error, bytesrepr, ApiError, ContractRef, SystemContractType, TransferResult, TransferredTo,
    URef, U512, UREF_SERIALIZED_LENGTH,
};

use crate::{
    contract_api::{self, runtime},
    ext_ffi,
    unwrap_or_revert::UnwrapOrRevert,
};

pub const MINT_NAME: &str = "mint";
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

/// Returns a read-only pointer to the Mint Contract.  Any failure will trigger `revert()` with a
/// `contract_api::Error`.
pub fn get_mint() -> ContractRef {
    get_system_contract(SystemContractType::Mint)
}

/// Returns a read-only pointer to the Proof of Stake Contract.  Any failure will trigger `revert()`
/// with a `contract_api::Error`.
pub fn get_proof_of_stake() -> ContractRef {
    get_system_contract(SystemContractType::ProofOfStake)
}

pub fn create_purse() -> PurseId {
    let purse_id_ptr = contract_api::alloc_bytes(PURSE_ID_SERIALIZED_LENGTH);
    unsafe {
        let ret = ext_ffi::create_purse(purse_id_ptr, PURSE_ID_SERIALIZED_LENGTH);
        if ret == 0 {
            let bytes = Vec::from_raw_parts(
                purse_id_ptr,
                PURSE_ID_SERIALIZED_LENGTH,
                PURSE_ID_SERIALIZED_LENGTH,
            );
            bytesrepr::deserialize(bytes).unwrap_or_revert()
        } else {
            runtime::revert(ApiError::PurseNotCreated)
        }
    }
}

/// Gets the balance of a given purse
pub fn get_balance(purse_id: PurseId) -> Option<U512> {
    let (purse_id_ptr, purse_id_size, _bytes) = contract_api::to_ptr(purse_id);

    let value_size = {
        let mut output_size = MaybeUninit::uninit();
        let ret =
            unsafe { ext_ffi::get_balance(purse_id_ptr, purse_id_size, output_size.as_mut_ptr()) };
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

/// Transfers `amount` of motes from default purse of the account to `target`
/// account. If `target` does not exist it will create it.
pub fn transfer_to_account(target: PublicKey, amount: U512) -> TransferResult {
    let (target_ptr, target_size, _bytes1) = contract_api::to_ptr(target);
    let (amount_ptr, amount_size, _bytes2) = contract_api::to_ptr(amount);
    let return_code =
        unsafe { ext_ffi::transfer_to_account(target_ptr, target_size, amount_ptr, amount_size) };
    TransferredTo::result_from(return_code)
}

/// Transfers `amount` of motes from `source` purse to `target` account.
/// If `target` does not exist it will create it.
pub fn transfer_from_purse_to_account(
    source: PurseId,
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

/// Transfers `amount` of motes from `source` purse to `target` purse.
pub fn transfer_from_purse_to_purse(
    source: PurseId,
    target: PurseId,
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
