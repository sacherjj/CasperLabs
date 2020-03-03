use types::{ApiError, URef};

pub trait AccountProvider {
    fn get_main_purse(&self) -> Result<URef, ApiError>;
}
