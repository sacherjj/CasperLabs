use crate::bytesrepr;
use crate::contract_api::{self, Error};

/// A trait which provides syntactic sugar for unwrapping a type or calling `contract_api::revert()`
/// if this fails.  It is implemented for `Result` and `Option`.
pub trait UnwrapOrRevert {
    /// The wrapped type.
    type Wrapped;

    /// Unwraps the value into its inner type or calls `contract_api::revert()` with a predetermined
    /// error code on failure.
    fn unwrap_or_revert(self) -> Self::Wrapped;
}

impl<T, E: Into<Error>> UnwrapOrRevert for Result<T, E> {
    type Wrapped = T;

    fn unwrap_or_revert(self) -> Self::Wrapped {
        self.unwrap_or_else(|error| contract_api::revert(error.into()))
    }
}

impl<T> UnwrapOrRevert for Result<T, bytesrepr::Error> {
    type Wrapped = T;

    fn unwrap_or_revert(self) -> Self::Wrapped {
        self.unwrap_or_else(|_| contract_api::revert(Error::Deserialize))
    }
}

impl<T> UnwrapOrRevert for Option<T> {
    type Wrapped = T;

    fn unwrap_or_revert(self) -> Self::Wrapped {
        self.unwrap_or_else(|| contract_api::revert(Error::None))
    }
}
