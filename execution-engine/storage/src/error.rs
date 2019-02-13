use common::bytesrepr;
use common::key::Key;
use rkv::error::StoreError;
use std::fmt;
use wasmi::HostError;

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Error {
    KeyNotFound { key: Key },
    TypeMismatch { expected: String, found: String },
    RkvError, //TODO: catpture error better
    BytesRepr(bytesrepr::Error),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl HostError for Error {}

impl From<StoreError> for Error {
    fn from(_e: StoreError) -> Self {
        Error::RkvError
    }
}

impl From<bytesrepr::Error> for Error {
    fn from(e: bytesrepr::Error) -> Self {
        Error::BytesRepr(e)
    }
}
