use std::fmt;

use common::bytesrepr;
use rkv::error::StoreError;
use wasmi::HostError;

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Error {
    // mateusz.gorski: I think that these errors should revert any changes made
    // to Global State and most probably kill the node.
    RkvError(String), //TODO: capture error better
    BytesRepr(bytesrepr::Error),
}


impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl HostError for Error {}

impl From<StoreError> for Error {
    fn from(e: StoreError) -> Self {
        Error::RkvError(e.to_string())
    }
}

impl From<bytesrepr::Error> for Error {
    fn from(e: bytesrepr::Error) -> Self {
        Error::BytesRepr(e)
    }
}
