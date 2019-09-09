pub use self::error::Error;
pub use self::executor::{Executor, WasmiExecutor};
pub use self::runtime::{
    create_rng, extract_access_rights_from_keys, extract_access_rights_from_urefs,
    instance_and_memory, Runtime,
};

mod error;
#[macro_use]
mod executor;
mod runtime;
#[cfg(test)]
mod tests;

pub const MINT_NAME: &str = "mint";
pub const POS_NAME: &str = "pos";
