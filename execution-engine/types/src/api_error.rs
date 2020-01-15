use core::{
    fmt::{self, Debug, Formatter},
    u16, u8,
};

use crate::{
    account::{
        AddKeyFailure, RemoveKeyFailure, SetThresholdFailure, TryFromIntError,
        TryFromSliceForPublicKeyError, UpdateKeyFailure,
    },
    bytesrepr,
    system_contract_errors::{mint, pos},
    CLValueError,
};

/// All `Error` variants defined in this library other than `Error::User` will convert to a `u32`
/// value less than or equal to `RESERVED_ERROR_MAX`.
const RESERVED_ERROR_MAX: u32 = u16::MAX as u32; // 0..=65535

/// Proof of Stake errors (defined in "contracts/system/pos/src/error.rs") will have this value
/// added to them when being converted to a `u32`.
const POS_ERROR_OFFSET: u32 = RESERVED_ERROR_MAX - u8::MAX as u32; // 65280..=65535

/// Mint errors (defined in "contracts/system/mint/src/error.rs") will have this value
/// added to them when being converted to a `u32`.
const MINT_ERROR_OFFSET: u32 = (POS_ERROR_OFFSET - 1) - u8::MAX as u32; // 65024..=65279

/// Variants to be passed to `runtime::revert()`.
///
/// Variants other than `Error::User` will represent a `u32` in the range `(0, u16::MAX]`, while
/// `Error::User` will represent a `u32` in the range `(u16::MAX, 2 * u16::MAX + 1]`.
///
/// Users can specify a C-style enum and implement `From` to ease usage of
/// `runtime::revert()`, e.g.
/// ```
/// use casperlabs_types::ApiError;
///
/// #[repr(u16)]
/// enum FailureCode {
///     Zero = 0,  // 65,536 as an ApiError::User
///     One,       // 65,537 as an ApiError::User
///     Two        // 65,538 as an ApiError::User
/// }
///
/// impl From<FailureCode> for ApiError {
///     fn from(code: FailureCode) -> Self {
///         ApiError::User(code as u16)
///     }
/// }
///
/// assert_eq!(ApiError::User(1), FailureCode::One.into());
/// assert_eq!(65_536, u32::from(ApiError::from(FailureCode::Zero)));
/// assert_eq!(65_538, u32::from(ApiError::from(FailureCode::Two)));
/// ```
#[derive(Copy, Clone, PartialEq, Eq)]
pub enum ApiError {
    /// Optional data was unexpectedly `None`.
    None,
    /// Specified argument not provided.
    MissingArgument,
    /// Argument not of correct type.
    InvalidArgument,
    /// Failed to deserialize a value.
    Deserialize,
    /// `read` returned an error.
    Read,
    /// The given key returned a `None` value.
    ValueNotFound,
    /// Failed to find a specified contract.
    ContractNotFound,
    /// A call to `get_key()` returned a failure.
    GetKey,
    /// The `Key` variant was not as expected.
    UnexpectedKeyVariant,
    /// The `Value` variant was not as expected.
    UnexpectedValueVariant,
    /// The `ContractRef` variant was not as expected.
    UnexpectedContractRefVariant,
    /// Invalid purse name given.
    InvalidPurseName,
    /// Invalid purse retrieved.
    InvalidPurse,
    /// Failed to upgrade contract at URef.
    UpgradeContractAtURef,
    /// Failed to transfer motes.
    Transfer,
    /// No access rights.
    NoAccessRights,
    /// A given type could be derived from a `Value`.
    ValueConversion,
    /// A given type could be derived from a `CLValue`.
    CLTypeMismatch,
    /// Early end of stream when deserializing.
    EarlyEndOfStream,
    /// Formatting error.
    FormattingError,
    /// Leftover bytes.
    LeftOverBytes,
    /// Out of memory error.
    OutOfMemoryError,
    /// Unable to add new associated key because maximum amount of keys is reached.
    MaxKeysLimit,
    /// Unable to add new associated key because given key already exists.
    DuplicateKey,
    /// Unable to add/update/remove new associated key due to insufficient permissions.
    PermissionDenied,
    /// Unable to update/remove a key that does exist.
    MissingKey,
    /// Unable to update/remove a key which would violate action threshold constraints.
    ThresholdViolation,
    /// New threshold should be lower or equal than deployment threshold.
    KeyManagementThresholdError,
    /// New threshold should be lower or equal than key management threshold.
    DeploymentThresholdError,
    /// Unable to set action threshold due to insufficient permissions.
    PermissionDeniedError,
    /// New threshold should be lower or equal than total weight of associated keys.
    InsufficientTotalWeight,
    /// Returns when contract tries to obtain URef to a system contract that does not exist.
    InvalidSystemContract,
    /// Failed to create a new purse.
    PurseNotCreated,
    /// An unhandled value, likely representing a bug in the code.
    Unhandled,
    /// Passing a buffer of a size that is too small to complete an operation
    BufferTooSmall,
    /// No data available in the host buffer.
    HostBufferEmpty,
    /// Data in the host buffer is full and should be consumed first by read operation
    HostBufferFull,
    /// Error specific to Mint contract.
    Mint(u8),
    /// Error specific to Proof of Stake contract.
    ProofOfStake(u8),
    /// User-specified value.  The internal `u16` value is added to `u16::MAX as u32 + 1` when an
    /// `Error::User` is converted to a `u32`.
    User(u16),
}

impl From<bytesrepr::Error> for ApiError {
    fn from(error: bytesrepr::Error) -> Self {
        match error {
            bytesrepr::Error::EarlyEndOfStream => ApiError::EarlyEndOfStream,
            bytesrepr::Error::FormattingError => ApiError::FormattingError,
            bytesrepr::Error::LeftOverBytes => ApiError::LeftOverBytes,
            bytesrepr::Error::OutOfMemoryError => ApiError::OutOfMemoryError,
        }
    }
}

impl From<AddKeyFailure> for ApiError {
    fn from(error: AddKeyFailure) -> Self {
        match error {
            AddKeyFailure::MaxKeysLimit => ApiError::MaxKeysLimit,
            AddKeyFailure::DuplicateKey => ApiError::DuplicateKey,
            AddKeyFailure::PermissionDenied => ApiError::PermissionDenied,
        }
    }
}

impl From<UpdateKeyFailure> for ApiError {
    fn from(error: UpdateKeyFailure) -> Self {
        match error {
            UpdateKeyFailure::MissingKey => ApiError::MissingKey,
            UpdateKeyFailure::PermissionDenied => ApiError::PermissionDenied,
            UpdateKeyFailure::ThresholdViolation => ApiError::ThresholdViolation,
        }
    }
}

impl From<RemoveKeyFailure> for ApiError {
    fn from(error: RemoveKeyFailure) -> Self {
        match error {
            RemoveKeyFailure::MissingKey => ApiError::MissingKey,
            RemoveKeyFailure::PermissionDenied => ApiError::PermissionDenied,
            RemoveKeyFailure::ThresholdViolation => ApiError::ThresholdViolation,
        }
    }
}

impl From<SetThresholdFailure> for ApiError {
    fn from(error: SetThresholdFailure) -> Self {
        match error {
            SetThresholdFailure::KeyManagementThresholdError => {
                ApiError::KeyManagementThresholdError
            }
            SetThresholdFailure::DeploymentThresholdError => ApiError::DeploymentThresholdError,
            SetThresholdFailure::PermissionDeniedError => ApiError::PermissionDeniedError,
            SetThresholdFailure::InsufficientTotalWeight => ApiError::InsufficientTotalWeight,
        }
    }
}

impl From<CLValueError> for ApiError {
    fn from(error: CLValueError) -> Self {
        match error {
            CLValueError::Serialization(bytesrepr_error) => bytesrepr_error.into(),
            CLValueError::Type(_) => ApiError::CLTypeMismatch,
        }
    }
}

impl From<TryFromIntError> for ApiError {
    fn from(_error: TryFromIntError) -> Self {
        ApiError::Unhandled
    }
}

impl From<TryFromSliceForPublicKeyError> for ApiError {
    fn from(_error: TryFromSliceForPublicKeyError) -> Self {
        ApiError::Deserialize
    }
}

impl From<mint::Error> for ApiError {
    fn from(error: mint::Error) -> Self {
        ApiError::Mint(error as u8)
    }
}

impl From<pos::Error> for ApiError {
    fn from(error: pos::Error) -> Self {
        ApiError::ProofOfStake(error as u8)
    }
}

impl From<ApiError> for u32 {
    fn from(error: ApiError) -> Self {
        match error {
            ApiError::None => 1,
            ApiError::MissingArgument => 2,
            ApiError::InvalidArgument => 3,
            ApiError::Deserialize => 4,
            ApiError::Read => 5,
            ApiError::ValueNotFound => 6,
            ApiError::ContractNotFound => 7,
            ApiError::GetKey => 8,
            ApiError::UnexpectedKeyVariant => 9,
            ApiError::UnexpectedValueVariant => 10,
            ApiError::UnexpectedContractRefVariant => 11,
            ApiError::InvalidPurseName => 12,
            ApiError::InvalidPurse => 13,
            ApiError::UpgradeContractAtURef => 14,
            ApiError::Transfer => 15,
            ApiError::NoAccessRights => 16,
            ApiError::ValueConversion => 17,
            ApiError::CLTypeMismatch => 18,
            ApiError::EarlyEndOfStream => 19,
            ApiError::FormattingError => 20,
            ApiError::LeftOverBytes => 21,
            ApiError::OutOfMemoryError => 22,
            ApiError::MaxKeysLimit => 23,
            ApiError::DuplicateKey => 24,
            ApiError::PermissionDenied => 25,
            ApiError::MissingKey => 26,
            ApiError::ThresholdViolation => 27,
            ApiError::KeyManagementThresholdError => 28,
            ApiError::DeploymentThresholdError => 29,
            ApiError::PermissionDeniedError => 30,
            ApiError::InsufficientTotalWeight => 31,
            ApiError::InvalidSystemContract => 32,
            ApiError::PurseNotCreated => 33,
            ApiError::Unhandled => 34,
            ApiError::BufferTooSmall => 35,
            ApiError::HostBufferEmpty => 36,
            ApiError::HostBufferFull => 37,
            ApiError::Mint(value) => MINT_ERROR_OFFSET + u32::from(value),
            ApiError::ProofOfStake(value) => POS_ERROR_OFFSET + u32::from(value),
            ApiError::User(value) => RESERVED_ERROR_MAX + 1 + u32::from(value),
        }
    }
}

impl Debug for ApiError {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        match self {
            ApiError::None => write!(f, "ApiError::None")?,
            ApiError::MissingArgument => write!(f, "ApiError::MissingArgument")?,
            ApiError::InvalidArgument => write!(f, "ApiError::InvalidArgument")?,
            ApiError::Deserialize => write!(f, "ApiError::Deserialize")?,
            ApiError::Read => write!(f, "ApiError::Read")?,
            ApiError::ValueNotFound => write!(f, "ApiError::ValueNotFound")?,
            ApiError::ContractNotFound => write!(f, "ApiError::ContractNotFound")?,
            ApiError::GetKey => write!(f, "ApiError::GetKey")?,
            ApiError::UnexpectedKeyVariant => write!(f, "ApiError::UnexpectedKeyVariant")?,
            ApiError::UnexpectedValueVariant => write!(f, "ApiError::UnexpectedValueVariant")?,
            ApiError::UnexpectedContractRefVariant => {
                write!(f, "ApiError::UnexpectedContractRefVariant")?
            }
            ApiError::InvalidPurseName => write!(f, "ApiError::InvalidPurseName")?,
            ApiError::InvalidPurse => write!(f, "ApiError::InvalidPurse")?,
            ApiError::UpgradeContractAtURef => write!(f, "ApiError::UpgradeContractAtURef")?,
            ApiError::Transfer => write!(f, "ApiError::Transfer")?,
            ApiError::NoAccessRights => write!(f, "ApiError::NoAccessRights")?,
            ApiError::ValueConversion => write!(f, "ApiError::ValueConversion")?,
            ApiError::CLTypeMismatch => write!(f, "ApiError::CLTypeMismatch")?,
            ApiError::EarlyEndOfStream => write!(f, "ApiError::EarlyEndOfStream")?,
            ApiError::FormattingError => write!(f, "ApiError::FormattingError")?,
            ApiError::LeftOverBytes => write!(f, "ApiError::LeftOverBytes")?,
            ApiError::OutOfMemoryError => write!(f, "ApiError::OutOfMemoryError")?,
            ApiError::MaxKeysLimit => write!(f, "ApiError::MaxKeysLimit")?,
            ApiError::DuplicateKey => write!(f, "ApiError::DuplicateKey")?,
            ApiError::PermissionDenied => write!(f, "ApiError::PermissionDenied")?,
            ApiError::MissingKey => write!(f, "ApiError::MissingKey")?,
            ApiError::ThresholdViolation => write!(f, "ApiError::ThresholdViolation")?,
            ApiError::KeyManagementThresholdError => {
                write!(f, "ApiError::KeyManagementThresholdError")?
            }
            ApiError::DeploymentThresholdError => write!(f, "ApiError::DeploymentThresholdError")?,
            ApiError::PermissionDeniedError => write!(f, "ApiError::PermissionDeniedError")?,
            ApiError::InsufficientTotalWeight => write!(f, "ApiError::InsufficientTotalWeight")?,
            ApiError::InvalidSystemContract => write!(f, "ApiError::InvalidSystemContract")?,
            ApiError::PurseNotCreated => write!(f, "ApiError::PurseNotCreated")?,
            ApiError::Unhandled => write!(f, "ApiError::Unhandled")?,
            ApiError::BufferTooSmall => write!(f, "ApiError::BufferTooSmall")?,
            ApiError::HostBufferEmpty => write!(f, "ApiError::HostBufferEmpty")?,
            ApiError::HostBufferFull => write!(f, "ApiError::HostBufferFull")?,
            ApiError::Mint(value) => write!(f, "ApiError::Mint({})", value)?,
            ApiError::ProofOfStake(value) => write!(f, "ApiError::ProofOfStake({})", value)?,
            ApiError::User(value) => write!(f, "ApiError::User({})", value)?,
        }
        write!(f, " [{}]", u32::from(*self))
    }
}

pub fn i32_from(result: Result<(), ApiError>) -> i32 {
    match result {
        Ok(()) => 0,
        Err(error) => u32::from(error) as i32,
    }
}

pub fn result_from(value: i32) -> Result<(), ApiError> {
    match value {
        0 => Ok(()),
        1 => Err(ApiError::None),
        2 => Err(ApiError::MissingArgument),
        3 => Err(ApiError::InvalidArgument),
        4 => Err(ApiError::Deserialize),
        5 => Err(ApiError::Read),
        6 => Err(ApiError::ValueNotFound),
        7 => Err(ApiError::ContractNotFound),
        8 => Err(ApiError::GetKey),
        9 => Err(ApiError::UnexpectedKeyVariant),
        10 => Err(ApiError::UnexpectedValueVariant),
        11 => Err(ApiError::UnexpectedContractRefVariant),
        12 => Err(ApiError::InvalidPurseName),
        13 => Err(ApiError::InvalidPurse),
        14 => Err(ApiError::UpgradeContractAtURef),
        15 => Err(ApiError::Transfer),
        16 => Err(ApiError::NoAccessRights),
        17 => Err(ApiError::ValueConversion),
        18 => Err(ApiError::CLTypeMismatch),
        19 => Err(ApiError::EarlyEndOfStream),
        20 => Err(ApiError::FormattingError),
        21 => Err(ApiError::LeftOverBytes),
        22 => Err(ApiError::OutOfMemoryError),
        23 => Err(ApiError::MaxKeysLimit),
        24 => Err(ApiError::DuplicateKey),
        25 => Err(ApiError::PermissionDenied),
        26 => Err(ApiError::MissingKey),
        27 => Err(ApiError::ThresholdViolation),
        28 => Err(ApiError::KeyManagementThresholdError),
        29 => Err(ApiError::DeploymentThresholdError),
        30 => Err(ApiError::PermissionDeniedError),
        31 => Err(ApiError::InsufficientTotalWeight),
        32 => Err(ApiError::InvalidSystemContract),
        33 => Err(ApiError::PurseNotCreated),
        34 => Err(ApiError::Unhandled),
        35 => Err(ApiError::BufferTooSmall),
        36 => Err(ApiError::HostBufferEmpty),
        37 => Err(ApiError::HostBufferFull),
        _ => {
            if value > RESERVED_ERROR_MAX as i32 && value <= (2 * RESERVED_ERROR_MAX + 1) as i32 {
                Err(ApiError::User(value as u16))
            } else if value >= POS_ERROR_OFFSET as i32 && value <= RESERVED_ERROR_MAX as i32 {
                Err(ApiError::ProofOfStake(value as u8))
            } else if value >= MINT_ERROR_OFFSET as i32 && value < POS_ERROR_OFFSET as i32 {
                Err(ApiError::Mint(value as u8))
            } else {
                Err(ApiError::Unhandled)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::{i32, u16, u8};

    use super::*;

    fn round_trip(result: Result<(), ApiError>) {
        let code = i32_from(result);
        assert_eq!(result, result_from(code));
    }

    #[test]
    fn error() {
        assert_eq!(65_024_u32, ApiError::Mint(0).into()); // MINT_ERROR_OFFSET == 65,024
        assert_eq!(65_279_u32, ApiError::Mint(u8::MAX).into());
        assert_eq!(65_280_u32, ApiError::ProofOfStake(0).into()); // POS_ERROR_OFFSET == 65,280
        assert_eq!(65_535_u32, ApiError::ProofOfStake(u8::MAX).into());
        assert_eq!(65_536_u32, ApiError::User(0).into()); // u16::MAX + 1
        assert_eq!(131_071_u32, ApiError::User(u16::MAX).into()); // 2 * u16::MAX + 1

        assert_eq!("ApiError::GetKey [8]", &format!("{:?}", ApiError::GetKey));
        assert_eq!(
            "ApiError::Mint(0) [65024]",
            &format!("{:?}", ApiError::Mint(0))
        );
        assert_eq!(
            "ApiError::Mint(255) [65279]",
            &format!("{:?}", ApiError::Mint(u8::MAX))
        );
        assert_eq!(
            "ApiError::ProofOfStake(0) [65280]",
            &format!("{:?}", ApiError::ProofOfStake(0))
        );
        assert_eq!(
            "ApiError::ProofOfStake(255) [65535]",
            &format!("{:?}", ApiError::ProofOfStake(u8::MAX))
        );
        assert_eq!(
            "ApiError::User(0) [65536]",
            &format!("{:?}", ApiError::User(0))
        );
        assert_eq!(
            "ApiError::User(65535) [131071]",
            &format!("{:?}", ApiError::User(u16::MAX))
        );

        assert_eq!(Err(ApiError::Unhandled), result_from(i32::MAX));
        assert_eq!(
            Err(ApiError::Unhandled),
            result_from(MINT_ERROR_OFFSET as i32 - 1)
        );
        assert_eq!(Err(ApiError::Unhandled), result_from(-1));
        assert_eq!(Err(ApiError::Unhandled), result_from(i32::MIN));

        round_trip(Ok(()));
        round_trip(Err(ApiError::None));
        round_trip(Err(ApiError::MissingArgument));
        round_trip(Err(ApiError::InvalidArgument));
        round_trip(Err(ApiError::Deserialize));
        round_trip(Err(ApiError::Read));
        round_trip(Err(ApiError::ValueNotFound));
        round_trip(Err(ApiError::ContractNotFound));
        round_trip(Err(ApiError::GetKey));
        round_trip(Err(ApiError::UnexpectedKeyVariant));
        round_trip(Err(ApiError::UnexpectedValueVariant));
        round_trip(Err(ApiError::UnexpectedContractRefVariant));
        round_trip(Err(ApiError::InvalidPurseName));
        round_trip(Err(ApiError::InvalidPurse));
        round_trip(Err(ApiError::UpgradeContractAtURef));
        round_trip(Err(ApiError::Transfer));
        round_trip(Err(ApiError::NoAccessRights));
        round_trip(Err(ApiError::ValueConversion));
        round_trip(Err(ApiError::CLTypeMismatch));
        round_trip(Err(ApiError::EarlyEndOfStream));
        round_trip(Err(ApiError::FormattingError));
        round_trip(Err(ApiError::LeftOverBytes));
        round_trip(Err(ApiError::OutOfMemoryError));
        round_trip(Err(ApiError::MaxKeysLimit));
        round_trip(Err(ApiError::DuplicateKey));
        round_trip(Err(ApiError::PermissionDenied));
        round_trip(Err(ApiError::MissingKey));
        round_trip(Err(ApiError::ThresholdViolation));
        round_trip(Err(ApiError::KeyManagementThresholdError));
        round_trip(Err(ApiError::DeploymentThresholdError));
        round_trip(Err(ApiError::PermissionDeniedError));
        round_trip(Err(ApiError::InsufficientTotalWeight));
        round_trip(Err(ApiError::InvalidSystemContract));
        round_trip(Err(ApiError::PurseNotCreated));
        round_trip(Err(ApiError::Unhandled));
        round_trip(Err(ApiError::BufferTooSmall));
        round_trip(Err(ApiError::HostBufferEmpty));
        round_trip(Err(ApiError::HostBufferFull));
        round_trip(Err(ApiError::Mint(0)));
        round_trip(Err(ApiError::Mint(u8::MAX)));
        round_trip(Err(ApiError::ProofOfStake(0)));
        round_trip(Err(ApiError::ProofOfStake(u8::MAX)));
        round_trip(Err(ApiError::User(0)));
        round_trip(Err(ApiError::User(u16::MAX)));
    }
}
