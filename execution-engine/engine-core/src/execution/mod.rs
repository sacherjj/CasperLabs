mod address_generator;
mod error;
#[macro_use]
mod executor;
#[cfg(test)]
mod tests;

pub use self::{
    address_generator::{AddressGenerator, AddressGeneratorBuilder},
    error::Error,
    executor::Executor,
};

pub const MINT_NAME: &str = "mint";
pub const POS_NAME: &str = "pos";
