use types::{ApiError, URef};

pub trait ProofOfStakeProvider {
    fn get_payment_purse(&mut self) -> Result<URef, ApiError>;
}
