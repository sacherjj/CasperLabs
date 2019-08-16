mod error;
#[macro_use]
mod executor;
mod runtime;
#[cfg(test)]
mod tests;

pub use self::error::Error;
pub use self::executor::{Executor, WasmiExecutor};
pub use self::runtime::{
    create_rng, extract_access_rights_from_keys, instance_and_memory, Runtime,
};

pub const MINT_NAME: &str = "mint";
pub const POS_NAME: &str = "pos";
