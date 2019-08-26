use std::sync;

use failure::Fail;
use lmdb as lmdb_external;
use wasmi;

use contract_ffi::bytesrepr;

use super::in_memory;

#[derive(Debug, Fail, PartialEq, Eq)]
pub enum Error {
    #[fail(display = "{}", _0)]
    Lmdb(#[fail(cause)] lmdb_external::Error),

    #[fail(display = "{}", _0)]
    BytesRepr(#[fail(cause)] bytesrepr::Error),

    #[fail(display = "Another thread panicked while holding a lock")]
    PoisonError,
}

impl wasmi::HostError for Error {}

impl From<lmdb_external::Error> for Error {
    fn from(error: lmdb_external::Error) -> Self {
        Error::Lmdb(error)
    }
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

impl From<in_memory::Error> for Error {
    fn from(error: in_memory::Error) -> Self {
        match error {
            in_memory::Error::BytesRepr(error) => Error::BytesRepr(error),
            in_memory::Error::PoisonError => Error::PoisonError,
        }
    }
}
