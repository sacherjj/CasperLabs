/// Implementation of error codes that are shared between contract
/// implementation and FFI.
use alloc::vec::Vec;
use core::convert::{TryFrom, TryInto};

use crate::bytesrepr::{self, FromBytes, ToBytes};
use crate::system_contracts::mint::purse_id::PurseIdError;

/// An enum error that is capable of carrying a value across FFI-Host
/// boundary.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
#[repr(u32)]
pub enum Error {
    InsufficientFunds = 0,
    SourceNotFound = 1,
    DestNotFound = 2,
    /// See [`PurseIdError::InvalidURef`]
    InvalidURef = 3,
    /// See [`PurseIdError::InvalidAccessRights`]
    InvalidAccessRights = 4,
}

impl From<PurseIdError> for Error {
    fn from(purse_id_error: PurseIdError) -> Error {
        match purse_id_error {
            PurseIdError::InvalidURef => Error::InvalidURef,
            PurseIdError::InvalidAccessRights(_) => {
                // This one does not carry state from PurseIdError to the
                // new Error enum. The reason is that Error is supposed to
                // be simple in serialization and deserialization, so extra
                // state is currently discarded.
                Error::InvalidAccessRights
            }
        }
    }
}

/// The error type returned when a construction
pub struct TryFromDeserializedU32Error(());

impl TryFrom<u32> for Error {
    type Error = TryFromDeserializedU32Error;

    fn try_from(value: u32) -> Result<Self, Self::Error> {
        match value {
            d if d == Error::InsufficientFunds as u32 => Ok(Error::InsufficientFunds),
            d if d == Error::SourceNotFound as u32 => Ok(Error::SourceNotFound),
            d if d == Error::DestNotFound as u32 => Ok(Error::DestNotFound),
            d if d == Error::InvalidURef as u32 => Ok(Error::InvalidURef),
            d if d == Error::InvalidAccessRights as u32 => Ok(Error::InvalidAccessRights),
            _ => Err(TryFromDeserializedU32Error(())),
        }
    }
}

impl ToBytes for Error {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let value = *self as u32;
        value.to_bytes()
    }
}

impl FromBytes for Error {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (value, rem): (u32, _) = FromBytes::from_bytes(bytes)?;
        let error: Error = value
            .try_into()
            // In case an Error variant is unable to be determined it would
            // return a FormattingError as if its unable to be correctly
            // deserialized.
            .map_err(|_| bytesrepr::Error::FormattingError)?;
        Ok((error, rem))
    }
}
