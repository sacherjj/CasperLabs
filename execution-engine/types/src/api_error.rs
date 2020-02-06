//! Contains [`ApiError`] and associated helper functions.

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

/// Errors which can be encountered while running a smart contract.
///
/// An `ApiError` can be converted to a `u32` in order to be passed via the execution engine's
/// `ext_ffi::revert()` function.  This means the information each variant can convey is limited.
///
/// The variants are split into numeric ranges as follows:
///
/// | Inclusive range | Variant(s)                                   |
/// | ----------------| ---------------------------------------------|
/// | [1, 65023]      | all except `Mint`, `ProofOfStake` and `User` |
/// | [65024, 65279]  | `Mint`                                       |
/// | [65280, 65535]  | `ProofOfStake`                               |
/// | [65536, 131071] | `User`                                       |
///
/// ## Mappings
///
/// The expanded mapping of all variants to their numerical equivalents is as follows:
/// ```
/// # use casperlabs_types::ApiError::{self, *};
/// # macro_rules! show_and_check {
/// #     ($lhs:literal => $rhs:expr) => {
/// #         assert_eq!($lhs as u32, ApiError::from($rhs).into());
/// #     };
/// # }
/// // General system errors:
/// # show_and_check!(
/// 1 => None
/// # );
/// # show_and_check!(
/// 2 => MissingArgument
/// # );
/// # show_and_check!(
/// 3 => InvalidArgument
/// # );
/// # show_and_check!(
/// 4 => Deserialize
/// # );
/// # show_and_check!(
/// 5 => Read
/// # );
/// # show_and_check!(
/// 6 => ValueNotFound
/// # );
/// # show_and_check!(
/// 7 => ContractNotFound
/// # );
/// # show_and_check!(
/// 8 => GetKey
/// # );
/// # show_and_check!(
/// 9 => UnexpectedKeyVariant
/// # );
/// # show_and_check!(
/// 10 => UnexpectedValueVariant
/// # );
/// # show_and_check!(
/// 11 => UnexpectedContractRefVariant
/// # );
/// # show_and_check!(
/// 12 => InvalidPurseName
/// # );
/// # show_and_check!(
/// 13 => InvalidPurse
/// # );
/// # show_and_check!(
/// 14 => UpgradeContractAtURef
/// # );
/// # show_and_check!(
/// 15 => Transfer
/// # );
/// # show_and_check!(
/// 16 => NoAccessRights
/// # );
/// # show_and_check!(
/// 17 => ValueConversion
/// # );
/// # show_and_check!(
/// 18 => CLTypeMismatch
/// # );
/// # show_and_check!(
/// 19 => EarlyEndOfStream
/// # );
/// # show_and_check!(
/// 20 => FormattingError
/// # );
/// # show_and_check!(
/// 21 => LeftOverBytes
/// # );
/// # show_and_check!(
/// 22 => OutOfMemoryError
/// # );
/// # show_and_check!(
/// 23 => MaxKeysLimit
/// # );
/// # show_and_check!(
/// 24 => DuplicateKey
/// # );
/// # show_and_check!(
/// 25 => PermissionDenied
/// # );
/// # show_and_check!(
/// 26 => MissingKey
/// # );
/// # show_and_check!(
/// 27 => ThresholdViolation
/// # );
/// # show_and_check!(
/// 28 => KeyManagementThresholdError
/// # );
/// # show_and_check!(
/// 29 => DeploymentThresholdError
/// # );
/// # show_and_check!(
/// 30 => PermissionDeniedError
/// # );
/// # show_and_check!(
/// 31 => InsufficientTotalWeight
/// # );
/// # show_and_check!(
/// 32 => InvalidSystemContract
/// # );
/// # show_and_check!(
/// 33 => PurseNotCreated
/// # );
/// # show_and_check!(
/// 34 => Unhandled
/// # );
/// # show_and_check!(
/// 35 => BufferTooSmall
/// # );
/// # show_and_check!(
/// 36 => HostBufferEmpty
/// # );
/// # show_and_check!(
/// 37 => HostBufferFull
/// # );
///
/// // Mint errors:
/// use casperlabs_types::system_contract_errors::mint::Error as MintError;
/// # show_and_check!(
/// 65_024 => MintError::InsufficientFunds
/// # );
/// # show_and_check!(
/// 65_025 => MintError::SourceNotFound
/// # );
/// # show_and_check!(
/// 65_026 => MintError::DestNotFound
/// # );
/// # show_and_check!(
/// 65_027 => MintError::InvalidURef
/// # );
/// # show_and_check!(
/// 65_028 => MintError::InvalidAccessRights
/// # );
/// # show_and_check!(
/// 65_029 => MintError::InvalidNonEmptyPurseCreation
/// # );
/// # show_and_check!(
/// 65_030 => MintError::StorageError
/// # );
/// # show_and_check!(
/// 65_031 => MintError::PurseNotFound
/// # );
/// # show_and_check!(
/// 65_126 => MintError::MissingArgument
/// # );
/// # show_and_check!(
/// 65_127 => MintError::InvalidArgument
/// # );
///
/// // Proof of stake errors:
/// use casperlabs_types::system_contract_errors::pos::Error as PosError;
/// # show_and_check!(
/// 65_280 => PosError::NotBonded
/// # );
/// # show_and_check!(
/// 65_281 => PosError::TooManyEventsInQueue
/// # );
/// # show_and_check!(
/// 65_282 => PosError::CannotUnbondLastValidator
/// # );
/// # show_and_check!(
/// 65_283 => PosError::SpreadTooHigh
/// # );
/// # show_and_check!(
/// 65_284 => PosError::MultipleRequests
/// # );
/// # show_and_check!(
/// 65_285 => PosError::BondTooSmall
/// # );
/// # show_and_check!(
/// 65_286 => PosError::BondTooLarge
/// # );
/// # show_and_check!(
/// 65_287 => PosError::UnbondTooLarge
/// # );
/// # show_and_check!(
/// 65_288 => PosError::BondTransferFailed
/// # );
/// # show_and_check!(
/// 65_289 => PosError::UnbondTransferFailed
/// # );
/// # show_and_check!(
/// 65_290 => PosError::MissingArgument
/// # );
/// # show_and_check!(
/// 65_291 => PosError::InvalidArgument
/// # );
/// # show_and_check!(
/// 65_292 => PosError::TimeWentBackwards
/// # );
/// # show_and_check!(
/// 65_293 => PosError::StakesNotFound
/// # );
/// # show_and_check!(
/// 65_294 => PosError::PaymentPurseNotFound
/// # );
/// # show_and_check!(
/// 65_295 => PosError::PaymentPurseKeyUnexpectedType
/// # );
/// # show_and_check!(
/// 65_296 => PosError::PaymentPurseBalanceNotFound
/// # );
/// # show_and_check!(
/// 65_297 => PosError::BondingPurseNotFound
/// # );
/// # show_and_check!(
/// 65_298 => PosError::BondingPurseKeyUnexpectedType
/// # );
/// # show_and_check!(
/// 65_299 => PosError::RefundPurseKeyUnexpectedType
/// # );
/// # show_and_check!(
/// 65_300 => PosError::RewardsPurseNotFound
/// # );
/// # show_and_check!(
/// 65_301 => PosError::RewardsPurseKeyUnexpectedType
/// # );
/// # show_and_check!(
/// 65_302 => PosError::StakesKeyDeserializationFailed
/// # );
/// # show_and_check!(
/// 65_303 => PosError::StakesDeserializationFailed
/// # );
/// # show_and_check!(
/// 65_304 => PosError::SystemFunctionCalledByUserAccount
/// # );
/// # show_and_check!(
/// 65_305 => PosError::InsufficientPaymentForAmountSpent
/// # );
/// # show_and_check!(
/// 65_306 => PosError::FailedTransferToRewardsPurse
/// # );
/// # show_and_check!(
/// 65_307 => PosError::FailedTransferToAccountPurse
/// # );
/// # show_and_check!(
/// 65_308 => PosError::SetRefundPurseCalledOutsidePayment
/// # );
///
/// // User-defined errors:
/// # show_and_check!(
/// 65_536 => User(0)
/// # );
/// # show_and_check!(
/// 65_537 => User(1)
/// # );
/// # show_and_check!(
/// 65_538 => User(2)
/// # );
/// # show_and_check!(
/// 131_071 => User(u16::max_value())
/// # );
/// ```
///
/// Users can specify a C-style enum and implement `From` to ease usage of
/// `casperlabs_contract::runtime::revert()`, e.g.
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
    /// `casperlabs_contract::storage::read()` returned an error.
    Read,
    /// The given key returned a `None` value.
    ValueNotFound,
    /// Failed to find a specified contract.
    ContractNotFound,
    /// A call to `casperlabs_contract::runtime::get_key()` returned a failure.
    GetKey,
    /// The [`Key`](crate::Key) variant was not as expected.
    UnexpectedKeyVariant,
    /// The `Value` variant was not as expected.
    UnexpectedValueVariant,
    /// The [`ContractRef`](crate::ContractRef) variant was not as expected.
    UnexpectedContractRefVariant,
    /// Invalid purse name given.
    InvalidPurseName,
    /// Invalid purse retrieved.
    InvalidPurse,
    /// Failed to upgrade contract at [`URef`](crate::URef).
    UpgradeContractAtURef,
    /// Failed to transfer motes.
    Transfer,
    /// The given [`URef`](crate::URef) has no access rights.
    NoAccessRights,
    /// A given type could not be constructed from a `Value`.
    ValueConversion,
    /// A given type could not be constructed from a [`CLValue`](crate::CLValue).
    CLTypeMismatch,
    /// Early end of stream while deserializing.
    EarlyEndOfStream,
    /// Formatting error while deserializing.
    FormattingError,
    /// Not all input bytes were consumed in [`deserialize`](crate::bytesrepr::deserialize).
    LeftOverBytes,
    /// Out of memory error.
    OutOfMemoryError,
    /// There are already [`MAX_ASSOCIATED_KEYS`](crate::account::MAX_ASSOCIATED_KEYS)
    /// [`PublicKey`](crate::account::PublicKey)s associated with the given account.
    MaxKeysLimit,
    /// The given [`PublicKey`](crate::account::PublicKey) is already associated with the given
    /// account.
    DuplicateKey,
    /// Unable to add/update/remove new associated [`PublicKey`](crate::account::PublicKey) due to
    /// insufficient permissions.
    PermissionDenied,
    /// The given [`PublicKey`](crate::account::PublicKey) is not associated with the given
    /// account.
    MissingKey,
    /// Removing/updating the given associated [`PublicKey`](crate::account::PublicKey) would cause
    /// the total [`Weight`](crate::account::Weight) of all remaining `PublicKey`s to fall below
    /// one of the action thresholds for the given account.
    ThresholdViolation,
    /// Setting the key-management threshold to a value lower than the deployment threshold is
    /// disallowed.
    KeyManagementThresholdError,
    /// Setting the deployment threshold to a value greater than any other threshold is disallowed.
    DeploymentThresholdError,
    /// Caller doesn't have sufficient permissions to perform the given action.
    PermissionDeniedError,
    /// Setting a threshold to a value greater than the total weight of associated keys is
    /// disallowed.
    InsufficientTotalWeight,
    /// The given `u32` doesn't map to a [`SystemContractType`](crate::SystemContractType).
    InvalidSystemContract,
    /// Failed to create a new purse.
    PurseNotCreated,
    /// An unhandled value, likely representing a bug in the code.
    Unhandled,
    /// The provided buffer is too small to complete an operation.
    BufferTooSmall,
    /// No data available in the host buffer.
    HostBufferEmpty,
    /// The host buffer has been set to a value and should be consumed first by a read operation.
    HostBufferFull,
    /// Error specific to Mint contract.
    Mint(u8),
    /// Error specific to Proof of Stake contract.
    ProofOfStake(u8),
    /// User-specified error code.  The internal `u16` value is added to `u16::MAX as u32 + 1` when
    /// an `Error::User` is converted to a `u32`.
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

// This conversion is not intended to be used by third party crates.
#[doc(hidden)]
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

// This function is not intended to be used by third party crates.
#[doc(hidden)]
pub fn i32_from(result: Result<(), ApiError>) -> i32 {
    match result {
        Ok(()) => 0,
        Err(error) => u32::from(error) as i32,
    }
}

/// Converts an `i32` to a `Result<(), ApiError>`, where `0` represents `Ok(())`, and all other
/// inputs are mapped to `Err(ApiError::<variant>)`.  The full list of mappings can be found in the
/// [docs for `ApiError`](ApiError#mappings).
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
