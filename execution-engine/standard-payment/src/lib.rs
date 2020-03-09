#![cfg_attr(not(feature = "std"), no_std)]

mod account_provider;
mod mint_provider;
mod proof_of_stake_provider;

use core::marker::Sized;

use types::{ApiError, U512};

pub use crate::{
    account_provider::AccountProvider, mint_provider::MintProvider,
    proof_of_stake_provider::ProofOfStakeProvider,
};

pub trait StandardPayment: AccountProvider + MintProvider + ProofOfStakeProvider + Sized {
    fn pay(&mut self, amount: U512) -> Result<(), ApiError> {
        let main_purse = self.get_main_purse()?;
        let payment_purse = self.get_payment_purse()?;
        self.transfer_purse_to_purse(main_purse, payment_purse, amount)
            .map_err(|_| ApiError::Transfer)
    }
}
