use crate::system_contracts::mint;

/// An aggregate enum error with variants for each system contract's error.
#[derive(Debug)]
pub enum Error {
    MintError(mint::error::Error),
}

impl From<mint::error::Error> for Error {
    fn from(error: mint::error::Error) -> Error {
        Error::MintError(error)
    }
}
