#![cfg_attr(not(test), no_std)]

extern crate alloc;

use alloc::{
    collections::{BTreeMap, BTreeSet},
    string::String,
};

use contract::{
    contract_api::{runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use proof_of_stake::{
    MintProvider, ProofOfStake, Queue, QueueProvider, RuntimeProvider, Stakes, StakesProvider,
};
use types::{
    account::PublicKey, system_contract_errors::pos::Error, ApiError, BlockTime, CLValue, Key,
    Phase, TransferResult, URef, U512,
};

const METHOD_BOND: &str = "bond";
const METHOD_UNBOND: &str = "unbond";
const METHOD_GET_PAYMENT_PURSE: &str = "get_payment_purse";
const METHOD_SET_REFUND_PURSE: &str = "set_refund_purse";
const METHOD_GET_REFUND_PURSE: &str = "get_refund_purse";
const METHOD_FINALIZE_PAYMENT: &str = "finalize_payment";

const BONDING_KEY: u8 = 1;
const UNBONDING_KEY: u8 = 2;

pub struct ProofOfStakeContract;

impl MintProvider for ProofOfStakeContract {
    fn transfer_purse_to_account(
        &mut self,
        source: URef,
        target: PublicKey,
        amount: U512,
    ) -> TransferResult {
        system::transfer_from_purse_to_account(source, target, amount)
    }

    fn transfer_purse_to_purse(
        &mut self,
        source: URef,
        target: URef,
        amount: U512,
    ) -> Result<(), ()> {
        system::transfer_from_purse_to_purse(source, target, amount).map_err(|_| ())
    }

    fn balance(&mut self, purse: URef) -> Option<U512> {
        system::get_balance(purse)
    }
}

impl QueueProvider for ProofOfStakeContract {
    /// Reads bonding queue from the local state of the contract.
    fn read_bonding(&mut self) -> Queue {
        storage::read_local(&BONDING_KEY)
            .unwrap_or_default()
            .unwrap_or_default()
    }

    /// Reads unbonding queue from the local state of the contract.
    fn read_unbonding(&mut self) -> Queue {
        storage::read_local(&UNBONDING_KEY)
            .unwrap_or_default()
            .unwrap_or_default()
    }

    /// Writes bonding queue to the local state of the contract.
    fn write_bonding(&mut self, queue: Queue) {
        storage::write_local(BONDING_KEY, queue);
    }

    /// Writes unbonding queue to the local state of the contract.
    fn write_unbonding(&mut self, queue: Queue) {
        storage::write_local(UNBONDING_KEY, queue);
    }
}

impl RuntimeProvider for ProofOfStakeContract {
    fn get_key(&self, name: &str) -> Option<Key> {
        runtime::get_key(name)
    }

    fn put_key(&mut self, name: &str, key: Key) {
        runtime::put_key(name, key)
    }

    fn remove_key(&mut self, name: &str) {
        runtime::remove_key(name)
    }

    fn get_phase(&self) -> Phase {
        runtime::get_phase()
    }

    fn get_block_time(&self) -> BlockTime {
        runtime::get_blocktime()
    }

    fn get_caller(&self) -> PublicKey {
        runtime::get_caller()
    }
}

impl StakesProvider for ProofOfStakeContract {
    /// Reads the current stakes from the contract's known urefs.
    fn read(&self) -> Result<Stakes, Error> {
        let mut stakes = BTreeMap::new();
        for (name, _) in runtime::list_named_keys() {
            let mut split_name = name.split('_');
            if Some("v") != split_name.next() {
                continue;
            }
            let hex_key = split_name
                .next()
                .ok_or(Error::StakesKeyDeserializationFailed)?;
            if hex_key.len() != 64 {
                return Err(Error::StakesKeyDeserializationFailed);
            }
            let mut key_bytes = [0u8; 32];
            let _bytes_written = base16::decode_slice(hex_key, &mut key_bytes)
                .map_err(|_| Error::StakesKeyDeserializationFailed)?;
            debug_assert!(_bytes_written == key_bytes.len());
            let pub_key = PublicKey::ed25519_from(key_bytes);
            let balance = split_name
                .next()
                .and_then(|b| U512::from_dec_str(b).ok())
                .ok_or(Error::StakesDeserializationFailed)?;
            stakes.insert(pub_key, balance);
        }
        if stakes.is_empty() {
            return Err(Error::StakesNotFound);
        }
        Ok(Stakes(stakes))
    }

    /// Writes the current stakes to the contract's known urefs.
    fn write(&mut self, stakes: &Stakes) {
        // Encode the stakes as a set of uref names.
        let mut new_urefs: BTreeSet<String> = stakes.strings().collect();
        // Remove and add urefs to update the contract's known urefs accordingly.
        for (name, _) in runtime::list_named_keys() {
            if name.starts_with("v_") && !new_urefs.remove(&name) {
                runtime::remove_key(&name);
            }
        }
        for name in new_urefs {
            runtime::put_key(&name, Key::Hash([0; 32]));
        }
    }
}

impl ProofOfStake for ProofOfStakeContract {}

pub fn delegate() {
    let mut pos_contract = ProofOfStakeContract;

    let method_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    match method_name.as_str() {
        // Type of this method: `fn bond(amount: U512, purse: URef)`
        METHOD_BOND => {
            if !cfg!(feature = "enable-bonding") {
                runtime::revert(ApiError::Unhandled)
            }

            let validator = runtime::get_caller();
            let amount: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let source_purse: URef = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            pos_contract
                .bond(validator, amount, source_purse)
                .unwrap_or_revert();
        }
        // Type of this method: `fn unbond(amount: Option<U512>)`
        METHOD_UNBOND => {
            if !cfg!(feature = "enable-bonding") {
                runtime::revert(ApiError::Unhandled)
            }

            let validator = runtime::get_caller();
            let maybe_amount = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            pos_contract
                .unbond(validator, maybe_amount)
                .unwrap_or_revert();
        }
        // Type of this method: `fn get_payment_purse() -> URef`
        METHOD_GET_PAYMENT_PURSE => {
            let rights_controlled_purse = pos_contract.get_payment_purse().unwrap_or_revert();
            let return_value = CLValue::from_t(rights_controlled_purse).unwrap_or_revert();
            runtime::ret(return_value);
        }
        // Type of this method: `fn set_refund_purse(purse: URef)`
        METHOD_SET_REFUND_PURSE => {
            let refund_purse: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            pos_contract
                .set_refund_purse(refund_purse)
                .unwrap_or_revert();
        }
        // Type of this method: `fn get_refund_purse() -> URef`
        METHOD_GET_REFUND_PURSE => {
            // We purposely choose to remove the access rights so that we do not
            // accidentally give rights for a purse to some contract that is not
            // supposed to have it.
            let maybe_refund_purse = pos_contract.get_refund_purse().unwrap_or_revert();
            let return_value = CLValue::from_t(maybe_refund_purse).unwrap_or_revert();
            runtime::ret(return_value);
        }
        // Type of this method: `fn finalize_payment()`
        METHOD_FINALIZE_PAYMENT => {
            let amount_spent: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let account: PublicKey = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            pos_contract
                .finalize_payment(amount_spent, account)
                .unwrap_or_revert();
        }
        _ => {}
    }
}
