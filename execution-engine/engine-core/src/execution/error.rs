use core::fmt;

use parity_wasm::elements;
use wasmi;

use contract_ffi::bytesrepr;
use contract_ffi::key::Key;
use contract_ffi::system_contracts;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::{
    AddKeyFailure, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure,
};
use engine_shared::transform::TypeMismatch;

use crate::resolvers::error::ResolverError;

#[derive(Debug)]
pub enum Error {
    Interpreter(wasmi::Error),
    Storage(engine_storage::error::Error),
    BytesRepr(bytesrepr::Error),
    KeyNotFound(Key),
    AccountNotFound(Key),
    TypeMismatch(TypeMismatch),
    InvalidAccess {
        required: AccessRights,
    },
    ForgedReference(URef),
    ArgIndexOutOfBounds(usize),
    URefNotFound(String),
    FunctionNotFound(String),
    ParityWasm(elements::Error),
    GasLimit,
    Ret(Vec<URef>),
    Rng(rand::Error),
    ResolverError(ResolverError),
    InvalidNonce {
        deploy_nonce: u64,
        expected_nonce: u64,
    },
    /// Reverts execution with a provided status
    Revert(u32),
    AddKeyFailure(AddKeyFailure),
    RemoveKeyFailure(RemoveKeyFailure),
    UpdateKeyFailure(UpdateKeyFailure),
    SetThresholdFailure(SetThresholdFailure),
    SystemContractError(system_contracts::error::Error),
    DeploymentAuthorizationFailure,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl wasmi::HostError for Error {}

impl From<!> for Error {
    fn from(error: !) -> Error {
        match error {}
    }
}

impl From<wasmi::Error> for Error {
    fn from(e: wasmi::Error) -> Self {
        Error::Interpreter(e)
    }
}

impl From<engine_storage::error::Error> for Error {
    fn from(e: engine_storage::error::Error) -> Self {
        Error::Storage(e)
    }
}

impl From<bytesrepr::Error> for Error {
    fn from(e: bytesrepr::Error) -> Self {
        Error::BytesRepr(e)
    }
}

impl From<elements::Error> for Error {
    fn from(e: elements::Error) -> Self {
        Error::ParityWasm(e)
    }
}

impl From<ResolverError> for Error {
    fn from(err: ResolverError) -> Error {
        Error::ResolverError(err)
    }
}

impl From<AddKeyFailure> for Error {
    fn from(err: AddKeyFailure) -> Error {
        Error::AddKeyFailure(err)
    }
}

impl From<RemoveKeyFailure> for Error {
    fn from(err: RemoveKeyFailure) -> Error {
        Error::RemoveKeyFailure(err)
    }
}

impl From<UpdateKeyFailure> for Error {
    fn from(err: UpdateKeyFailure) -> Error {
        Error::UpdateKeyFailure(err)
    }
}

impl From<SetThresholdFailure> for Error {
    fn from(err: SetThresholdFailure) -> Error {
        Error::SetThresholdFailure(err)
    }
}

impl From<system_contracts::error::Error> for Error {
    fn from(error: system_contracts::error::Error) -> Error {
        Error::SystemContractError(error)
    }
}
