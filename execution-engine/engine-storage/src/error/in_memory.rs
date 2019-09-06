use std::sync;

use failure::Fail;

use contract_ffi::bytesrepr;

#[derive(Debug, Fail, PartialEq, Eq)]
pub enum Error {
    #[fail(display = "{}", _0)]
    BytesRepr(#[fail(cause)] bytesrepr::Error),

    #[fail(display = "Another thread panicked while holding a lock")]
    PoisonError,
}

impl From<bytesrepr::Error> for Error {
    fn from(error: bytesrepr::Error) -> Self {
        Error::BytesRepr(error)
    }
}

impl<T> From<sync::PoisonError<T>> for Error {
    fn from(_error: sync::PoisonError<T>) -> Self {
        Error::PoisonError
    }
}
