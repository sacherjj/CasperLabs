use common::bytesrepr;
use lmdb;
use wasmi;

#[derive(Debug, Fail, PartialEq, Eq)]
pub enum Error {
    #[fail(display = "{}", _0)]
    Lmdb(#[fail(cause)] lmdb::Error),

    #[fail(display = "{}", _0)]
    BytesRepr(#[fail(cause)] bytesrepr::Error),

    #[fail(display = "Another thread panicked while holding a lock")]
    PoisonError,
}

impl wasmi::HostError for Error {}

impl From<lmdb::Error> for Error {
    fn from(e: lmdb::Error) -> Self {
        Error::Lmdb(e)
    }
}

impl From<bytesrepr::Error> for Error {
    fn from(e: bytesrepr::Error) -> Self {
        Error::BytesRepr(e)
    }
}

impl<T> From<std::sync::PoisonError<T>> for Error {
    fn from(_e: std::sync::PoisonError<T>) -> Self {
        Error::PoisonError
    }
}
