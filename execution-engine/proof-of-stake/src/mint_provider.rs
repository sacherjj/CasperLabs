use types::{
    account::{PublicKey, PurseId},
    ApiError, TransferResult, U512,
};

pub trait MintProvider {
    fn transfer_from_purse_to_account(
        source: PurseId,
        target: PublicKey,
        amount: U512,
    ) -> TransferResult;

    fn transfer_from_purse_to_purse(
        source: PurseId,
        target: PurseId,
        amount: U512,
    ) -> Result<(), ApiError>;

    fn get_balance(purse: PurseId) -> Option<U512>;
}
