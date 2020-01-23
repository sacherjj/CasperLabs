pub mod mint;
pub mod pos;

/// An aggregate enum error with variants for each system contract's error.
#[derive(Debug)]
pub enum Error {
    MintError(mint::Error),
    PosError(pos::Error),
}

impl From<mint::Error> for Error {
    fn from(error: mint::Error) -> Error {
        Error::MintError(error)
    }
}

impl From<pos::Error> for Error {
    fn from(error: pos::Error) -> Error {
        Error::PosError(error)
    }
}
