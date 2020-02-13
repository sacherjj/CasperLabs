//! Home of error types returned by system contracts.

pub mod mint;
pub mod pos;

/// An aggregate enum error with variants for each system contract's error.
#[derive(Debug)]
pub enum Error {
    /// Contains a [`mint::Error`].
    Mint(mint::Error),
    /// Contains a [`pos::Error`].
    Pos(pos::Error),
}

impl From<mint::Error> for Error {
    fn from(error: mint::Error) -> Error {
        Error::Mint(error)
    }
}

impl From<pos::Error> for Error {
    fn from(error: pos::Error) -> Error {
        Error::Pos(error)
    }
}
