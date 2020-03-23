use types::{ApiError, URef, U512};

pub trait MintProvider {
    fn transfer_purse_to_purse(
        &mut self,
        source: URef,
        target: URef,
        amount: U512,
    ) -> Result<(), ApiError>;
}
