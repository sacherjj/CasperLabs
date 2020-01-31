use types::{system_contract_errors::mint::Error, URef, U512};

pub trait Mint {
    fn mint(&self, initial_balance: U512) -> Result<URef, Error>;

    fn lookup(&self, p: URef) -> Option<URef>;

    fn create(&self) -> URef;

    fn transfer(&self, source: URef, dest: URef, amount: U512) -> Result<(), Error>;
}
