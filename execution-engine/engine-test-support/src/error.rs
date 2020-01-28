use std::result;

use engine_shared::transform::TypeMismatch;
use types::CLValueError;

/// The error type returned by any casperlabs-engine-test-support operation.
#[derive(Eq, PartialEq, Ord, PartialOrd, Clone, Hash, Debug)]
pub struct Error {
    inner: String,
}

impl From<String> for Error {
    fn from(error: String) -> Self {
        Error { inner: error }
    }
}

impl From<CLValueError> for Error {
    fn from(error: CLValueError) -> Self {
        Error {
            inner: format!("{:?}", error),
        }
    }
}

impl From<TypeMismatch> for Error {
    fn from(error: TypeMismatch) -> Self {
        Error {
            inner: format!("{:?}", error),
        }
    }
}

/// A specialized `std::result::Result` for this crate.
pub type Result<T> = result::Result<T, Error>;
