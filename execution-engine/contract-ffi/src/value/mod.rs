//! Home of [`CLValue`](crate::value::CLValue), the type representing data stored and manipulated on
//! the CasperLabs Platform.

pub mod account;
mod cl_type;
mod cl_value;
mod protocol_version;
mod semver;
mod uint;

pub use self::{
    cl_type::{named_key_type, CLType, CLTyped},
    cl_value::{CLTypeMismatch, CLValue, CLValueError},
    protocol_version::ProtocolVersion,
    semver::SemVer,
    uint::{U128, U256, U512},
};
