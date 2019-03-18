use common::bytesrepr;
use rkv;
use wasmi;

#[derive(Debug, Fail)]
pub enum Error {
    #[fail(display = "{}", _0)]
    Rkv(#[fail(cause)] rkv::error::StoreError),

    #[fail(display = "{}", _0)]
    BytesRepr(#[fail(cause)] bytesrepr::Error),
}

impl wasmi::HostError for Error {}

impl From<rkv::error::StoreError> for Error {
    fn from(e: rkv::error::StoreError) -> Self {
        Error::Rkv(e)
    }
}

impl From<bytesrepr::Error> for Error {
    fn from(e: bytesrepr::Error) -> Self {
        Error::BytesRepr(e)
    }
}
