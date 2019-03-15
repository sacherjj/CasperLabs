use std::fmt;

use common::bytesrepr;
use rkv::error::StoreError;
use shared::newtypes::Blake2bHash;
use transform::TypeMismatch;
use wasmi::HostError;

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct RootNotFound(pub Blake2bHash);

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Error {
    TransformTypeMismatch(TypeMismatch),
    // mateusz.gorski: I think that these errors should revert any changes made
    // to Global State and most probably kill the node.
    RkvError(String), //TODO: capture error better
    BytesRepr(bytesrepr::Error),
}

pub type GlobalStateError = Error;

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl HostError for GlobalStateError {}

impl From<StoreError> for GlobalStateError {
    fn from(e: StoreError) -> Self {
        Error::RkvError(e.to_string())
    }
}

impl From<bytesrepr::Error> for GlobalStateError {
    fn from(e: bytesrepr::Error) -> Self {
        Error::BytesRepr(e)
    }
}

impl From<TypeMismatch> for GlobalStateError {
    fn from(tm: TypeMismatch) -> Self {
        Error::TransformTypeMismatch(tm)
    }
}
