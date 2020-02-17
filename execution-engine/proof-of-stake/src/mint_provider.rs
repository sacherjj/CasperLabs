use types::{account::PublicKey, TransferResult, URef, U512};

pub trait MintProvider {
    fn transfer_from_purse_to_account(
        source: URef,
        target: PublicKey,
        amount: U512,
    ) -> TransferResult;

    fn transfer_from_purse_to_purse(source: URef, target: URef, amount: U512) -> Result<(), ()>;

    fn get_balance(purse: URef) -> Option<U512>;
}
