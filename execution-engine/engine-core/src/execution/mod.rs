mod address_generator;
mod error;
#[macro_use]
mod executor;
mod runtime;
#[cfg(test)]
mod tests;

pub use self::{
    address_generator::{AddressGenerator, AddressGeneratorBuilder},
    error::Error,
    executor::Executor,
    runtime::{
        extract_access_rights_from_keys, extract_access_rights_from_urefs, instance_and_memory,
        Runtime,
    },
};

pub const MINT_NAME: &str = "mint";
pub const POS_NAME: &str = "pos";

pub(crate) const FN_STORE_ID_INITIAL: u32 = 0;
