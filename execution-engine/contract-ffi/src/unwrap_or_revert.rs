use crate::bytesrepr;
use crate::contract_api::{self, Error};

/// A trait which provides syntactic sugar for unwrapping a type or calling `contract_api::revert()`
/// if this fails.  It is implemented for `Result` and `Option`.
pub trait UnwrapOrRevert<T> {
    /// Unwraps the value into its inner type or calls `contract_api::revert()` with a predetermined
    /// error code on failure.
    fn unwrap_or_revert(self) -> T;

    /// Unwraps the value into its inner type or calls `contract_api::revert()` with the provided
    /// `error` on failure.
    fn unwrap_or_revert_with<E: Into<Error>>(self, error: E) -> T;
}

impl<T, E: Into<Error>> UnwrapOrRevert<T> for Result<T, E> {
    fn unwrap_or_revert(self) -> T {
        self.unwrap_or_else(|error| contract_api::revert(error.into()))
    }

    fn unwrap_or_revert_with<F: Into<Error>>(self, error: F) -> T {
        self.unwrap_or_else(|_| contract_api::revert(error.into()))
    }
}

impl<T> UnwrapOrRevert<T> for Result<T, bytesrepr::Error> {
    fn unwrap_or_revert(self) -> T {
        self.unwrap_or_else(|_| contract_api::revert(Error::Deserialize))
    }

    fn unwrap_or_revert_with<E: Into<Error>>(self, error: E) -> T {
        self.unwrap_or_else(|_| contract_api::revert(error.into()))
    }
}

impl<T> UnwrapOrRevert<T> for Option<T> {
    fn unwrap_or_revert(self) -> T {
        self.unwrap_or_else(|| contract_api::revert(Error::None))
    }

    fn unwrap_or_revert_with<E: Into<Error>>(self, error: E) -> T {
        self.unwrap_or_else(|| contract_api::revert(error.into()))
    }
}
