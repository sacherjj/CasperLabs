use core::fmt;

use parity_wasm::elements;
use wasmi;

use engine_shared::transform::TypeMismatch;
use types::{
    account::{AddKeyFailure, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure},
    bytesrepr, system_contract_errors, AccessRights, CLValueError, Key, URef,
};

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
    URefNotFound(String),
    FunctionNotFound(String),
    ParityWasm(elements::Error),
    GasLimit,
    Ret(Vec<URef>),
    Rng(rand::Error),
    ResolverError(ResolverError),
    /// Reverts execution with a provided status
    Revert(u32),
    AddKeyFailure(AddKeyFailure),
    RemoveKeyFailure(RemoveKeyFailure),
    UpdateKeyFailure(UpdateKeyFailure),
    SetThresholdFailure(SetThresholdFailure),
    SystemContractError(system_contract_errors::Error),
    DeploymentAuthorizationFailure,
    ExpectedReturnValue,
    UnexpectedReturnValue,
    InvalidContext,
    IncompatibleProtocolMajorVersion {
        expected: u32,
        actual: u32,
    },
    CLValue(CLValueError),
    HostBufferEmpty,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl wasmi::HostError for Error {}

impl From<!> for Error {
    fn from(error: !) -> Self {
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
    fn from(err: ResolverError) -> Self {
        Error::ResolverError(err)
    }
}

impl From<AddKeyFailure> for Error {
    fn from(err: AddKeyFailure) -> Self {
        Error::AddKeyFailure(err)
    }
}

impl From<RemoveKeyFailure> for Error {
    fn from(err: RemoveKeyFailure) -> Self {
        Error::RemoveKeyFailure(err)
    }
}

impl From<UpdateKeyFailure> for Error {
    fn from(err: UpdateKeyFailure) -> Self {
        Error::UpdateKeyFailure(err)
    }
}

impl From<SetThresholdFailure> for Error {
    fn from(err: SetThresholdFailure) -> Self {
        Error::SetThresholdFailure(err)
    }
}

impl From<system_contract_errors::Error> for Error {
    fn from(error: system_contract_errors::Error) -> Self {
        Error::SystemContractError(error)
    }
}

impl From<CLValueError> for Error {
    fn from(e: CLValueError) -> Self {
        Error::CLValue(e)
    }
}
