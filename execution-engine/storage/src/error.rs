use common::key::Key;
use std::fmt;
use wasmi::HostError;

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Error {
    KeyNotFound { key: Key },
    TypeMismatch { expected: String, found: String },
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl HostError for Error {}
