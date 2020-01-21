use std::path::PathBuf;

use crate::{Hash, URefAddr};

/// Represents the types of session or payment code.
pub enum Code {
    /// The filesystem path of compiled Wasm code.
    Path(PathBuf),
    /// A named key providing the location of a stored contract.
    NamedKey(String),
    /// A [`URef`](https://docs.rs/casperlabs-types/latest/casperlabs_types/struct.URef.html) to a stored contract.
    URef(URefAddr),
    /// A hash providing the location of a stored contract.
    Hash(Hash),
}
