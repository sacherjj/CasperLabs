use contract::contract_api::system;
use proof_of_stake::MintProvider;
use types::{
    account::{PublicKey, PurseId},
    ApiError, TransferResult, U512,
};

#[allow(dead_code)]
pub struct ContractMint;

impl MintProvider for ContractMint {
    fn transfer_from_purse_to_account(
        source: PurseId,
        target: PublicKey,
        amount: U512,
    ) -> TransferResult {
        system::transfer_from_purse_to_account(source, target, amount)
    }

    fn transfer_from_purse_to_purse(
        source: PurseId,
        target: PurseId,
        amount: U512,
    ) -> Result<(), ApiError> {
        system::transfer_from_purse_to_purse(source, target, amount)
    }

    fn get_balance(purse: PurseId) -> Option<U512> {
        system::get_balance(purse)
    }
}
