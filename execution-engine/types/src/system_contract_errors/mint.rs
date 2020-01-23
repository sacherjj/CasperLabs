/// Implementation of error codes that are shared between contract implementation and FFI.
use alloc::{fmt, vec::Vec};
use core::convert::{TryFrom, TryInto};

use failure::Fail;

use crate::{
    bytesrepr::{self, FromBytes, ToBytes},
    AccessRights, CLType, CLTyped,
};

/// An enum error that is capable of carrying a value across FFI-Host boundary.
#[derive(Fail, Debug, Copy, Clone, PartialEq, Eq)]
#[repr(u8)]
pub enum Error {
    #[fail(display = "Insufficient funds")]
    InsufficientFunds = 0,
    #[fail(display = "Source not found")]
    SourceNotFound = 1,
    #[fail(display = "Destination not found")]
    DestNotFound = 2,
    /// See [`PurseIdError::InvalidURef`]
    #[fail(display = "Invalid URef")]
    InvalidURef = 3,
    /// See [`PurseIdError::InvalidAccessRights`]
    #[fail(display = "Invalid AccessRights")]
    InvalidAccessRights = 4,
    #[fail(display = "Invalid non-empty purse creation")]
    InvalidNonEmptyPurseCreation = 5,
    #[fail(display = "Missing argument")]
    MissingArgument = 102,
    #[fail(display = "Passed argument is invalid")]
    InvalidArgument = 103,
}

impl From<PurseIdError> for Error {
    fn from(purse_id_error: PurseIdError) -> Error {
        match purse_id_error {
            PurseIdError::InvalidURef => Error::InvalidURef,
            PurseIdError::InvalidAccessRights(_) => {
                // This one does not carry state from PurseIdError to the new Error enum. The reason
                // is that Error is supposed to be simple in serialization and deserialization, so
                // extra state is currently discarded.
                Error::InvalidAccessRights
            }
        }
    }
}

impl CLTyped for Error {
    fn cl_type() -> CLType {
        CLType::U8
    }
}

/// The error type returned when construction from `u8` fails
pub struct TryFromU8ForError(());

impl TryFrom<u8> for Error {
    type Error = TryFromU8ForError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            d if d == Error::InsufficientFunds as u8 => Ok(Error::InsufficientFunds),
            d if d == Error::SourceNotFound as u8 => Ok(Error::SourceNotFound),
            d if d == Error::DestNotFound as u8 => Ok(Error::DestNotFound),
            d if d == Error::InvalidURef as u8 => Ok(Error::InvalidURef),
            d if d == Error::InvalidAccessRights as u8 => Ok(Error::InvalidAccessRights),
            d if d == Error::MissingArgument as u8 => Ok(Error::MissingArgument),
            d if d == Error::InvalidArgument as u8 => Ok(Error::InvalidArgument),
            d if d == Error::InvalidNonEmptyPurseCreation as u8 => {
                Ok(Error::InvalidNonEmptyPurseCreation)
            }
            _ => Err(TryFromU8ForError(())),
        }
    }
}

impl ToBytes for Error {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let value = *self as u8;
        value.to_bytes()
    }
}

impl FromBytes for Error {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (value, rem): (u8, _) = FromBytes::from_bytes(bytes)?;
        let error: Error = value
            .try_into()
            // In case an Error variant is unable to be determined it would return a FormattingError
            // as if its unable to be correctly deserialized.
            .map_err(|_| bytesrepr::Error::FormattingError)?;
        Ok((error, rem))
    }
}

#[derive(Debug, Copy, Clone)]
pub enum PurseIdError {
    InvalidURef,
    InvalidAccessRights(Option<AccessRights>),
}

impl fmt::Display for PurseIdError {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            PurseIdError::InvalidURef => write!(f, "invalid uref"),
            PurseIdError::InvalidAccessRights(maybe_access_rights) => {
                write!(f, "invalid access rights: {:?}", maybe_access_rights)
            }
        }
    }
}
