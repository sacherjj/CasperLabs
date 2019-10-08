use core::fmt::{self, Debug, Formatter};
use core::{u16, u8};

/// All `Error` variants defined in this library other than `Error::User` will convert to a `u32`
/// value less than or equal to `RESERVED_ERROR_MAX`.
const RESERVED_ERROR_MAX: u32 = u16::MAX as u32;

/// Proof of Stake errors (defined in "contracts/system/pos/src/error.rs") will have this value
/// added to them when being converted to a `u32`.
const POS_ERROR_OFFSET: u32 = RESERVED_ERROR_MAX - u8::MAX as u32;

/// Variants to be passed to `contract_api::revert()`.
///
/// Variants other than `Error::User` will represent a `u32` in the range `(0, u16::MAX]`, while
/// `Error::User` will represent a `u32` in the range `(u16::MAX, 2 * u16::MAX + 1]`.
///
/// Users can specify a C-style enum and implement `From` to ease usage of `contract_api::revert()`,
/// e.g.
/// ```
/// use casperlabs_contract_ffi::contract_api::Error;
///
/// #[repr(u16)]
/// enum FailureCode {
///     Zero = 0,  // 65,536 as an Error::User
///     One,       // 65,537 as an Error::User
///     Two        // 65,538 as an Error::User
/// }
///
/// impl From<FailureCode> for Error {
///     fn from(code: FailureCode) -> Self {
///         Error::User(code as u16)
///     }
/// }
///
/// assert_eq!(Error::User(1), FailureCode::One.into());
/// assert_eq!(65_536, u32::from(Error::from(FailureCode::Zero)));
/// assert_eq!(65_538, u32::from(Error::from(FailureCode::Two)));
/// ```
#[derive(Copy, Clone, PartialEq, Eq)]
pub enum Error {
    /// A call to `get_uref()` returned a failure.
    GetURef,
    /// Failed to deserialize a value.
    Deserialize,
    /// Failed to find a specified contract.
    ContractNotFound,
    /// The `Key` variant was not as expected.
    UnexpectedKeyVariant,
    /// The `Value` variant was not as expected.
    UnexpectedValueVariant,
    /// The `ContractPointer` variant was not as expected.
    UnexpectedContractPointerVariant,
    /// `read` returned an error.
    Read,
    /// The given key returned a `None` value.
    ValueNotFound,
    /// Failed to initialize a mint purse.
    MintFailure,
    /// Invalid purse name given.
    InvalidPurseName,
    /// Invalid purse retrieved.
    InvalidPurse,
    /// Specified argument not provided.
    MissingArgument,
    /// Argument not of correct type.
    InvalidArgument,
    /// Failed to upgrade contract at URef.
    UpgradeContractAtURef,
    /// Failed to transfer motes.
    Transfer,
    /// No access rights.
    NoAccessRights,
    /// Optional data was unexpectedly `None`.
    None,
    /// Error specific to Proof of Stake contract.
    ProofOfStake(u8),
    /// User-specified value.  The internal `u16` value is added to `u16::MAX as u32 + 1` when an
    /// `Error::User` is converted to a `u32`.
    User(u16),
}

impl From<Error> for u32 {
    fn from(error: Error) -> Self {
        match error {
            Error::GetURef => 1,
            Error::Deserialize => 2,
            Error::ContractNotFound => 3,
            Error::UnexpectedKeyVariant => 4,
            Error::UnexpectedValueVariant => 5,
            Error::UnexpectedContractPointerVariant => 6,
            Error::Read => 7,
            Error::ValueNotFound => 8,
            Error::MintFailure => 9,
            Error::InvalidPurseName => 10,
            Error::InvalidPurse => 11,
            Error::MissingArgument => 12,
            Error::InvalidArgument => 13,
            Error::UpgradeContractAtURef => 14,
            Error::Transfer => 15,
            Error::NoAccessRights => 16,
            Error::None => 17,
            Error::ProofOfStake(value) => POS_ERROR_OFFSET + u32::from(value),
            Error::User(value) => RESERVED_ERROR_MAX + 1 + u32::from(value),
        }
    }
}

impl Debug for Error {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        match self {
            Error::GetURef => write!(f, "Error::GetURef")?,
            Error::Deserialize => write!(f, "Error::Deserialize")?,
            Error::ContractNotFound => write!(f, "Error::ContractNotFound")?,
            Error::UnexpectedKeyVariant => write!(f, "Error::UnexpectedKeyVariant")?,
            Error::UnexpectedValueVariant => write!(f, "Error::UnexpectedValueVariant")?,
            Error::UnexpectedContractPointerVariant => {
                write!(f, "Error::UnexpectedContractPointerVariant")?
            }
            Error::Read => write!(f, "Error::Read")?,
            Error::ValueNotFound => write!(f, "Error::ValueNotFound")?,
            Error::MintFailure => write!(f, "Error::MintFailure")?,
            Error::InvalidPurseName => write!(f, "Error::InvalidPurseName")?,
            Error::InvalidPurse => write!(f, "Error::InvalidPurse")?,
            Error::MissingArgument => write!(f, "Error::MissingArgument")?,
            Error::InvalidArgument => write!(f, "Error::InvalidArgument")?,
            Error::UpgradeContractAtURef => write!(f, "Error::UpgradeContractAtURef")?,
            Error::Transfer => write!(f, "Error::Transfer")?,
            Error::NoAccessRights => write!(f, "Error::NoAccessRights")?,
            Error::None => write!(f, "Error::None")?,
            Error::ProofOfStake(value) => write!(f, "Error::ProofOfStake({})", value)?,
            Error::User(value) => write!(f, "Error::User({})", value)?,
        }
        write!(f, " [{}]", u32::from(*self))
    }
}

pub fn i32_from(result: Result<(), Error>) -> i32 {
    match result {
        Ok(()) => 0,
        Err(error) => u32::from(error) as i32,
    }
}

pub fn result_from(value: i32) -> Result<(), Error> {
    match value {
        0 => Ok(()),
        1 => Err(Error::GetURef),
        2 => Err(Error::Deserialize),
        3 => Err(Error::ContractNotFound),
        4 => Err(Error::UnexpectedKeyVariant),
        5 => Err(Error::UnexpectedValueVariant),
        6 => Err(Error::UnexpectedContractPointerVariant),
        7 => Err(Error::Read),
        8 => Err(Error::ValueNotFound),
        9 => Err(Error::MintFailure),
        10 => Err(Error::InvalidPurseName),
        11 => Err(Error::InvalidPurse),
        12 => Err(Error::MissingArgument),
        13 => Err(Error::InvalidArgument),
        14 => Err(Error::UpgradeContractAtURef),
        15 => Err(Error::Transfer),
        16 => Err(Error::NoAccessRights),
        17 => Err(Error::None),
        _ => {
            if value > RESERVED_ERROR_MAX as i32 && value <= (2 * RESERVED_ERROR_MAX + 1) as i32 {
                Err(Error::User(value as u16))
            } else if value >= POS_ERROR_OFFSET as i32 {
                Err(Error::ProofOfStake(value as u8))
            } else {
                unreachable!()
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::{u16, u8};

    fn round_trip(result: Result<(), Error>) {
        let code = i32_from(result);
        assert_eq!(result, result_from(code));
    }

    #[test]
    fn error() {
        assert_eq!(65_280_u32, Error::ProofOfStake(0).into()); // POS_ERROR_OFFSET == 65,280
        assert_eq!(65_535_u32, Error::ProofOfStake(u8::MAX).into());
        assert_eq!(65_536_u32, Error::User(0).into()); // u16::MAX + 1
        assert_eq!(131_071_u32, Error::User(u16::MAX).into()); // 2 * u16::MAX + 1

        assert_eq!("Error::GetURef [1]", &format!("{:?}", Error::GetURef));
        assert_eq!(
            "Error::ProofOfStake(0) [65280]",
            &format!("{:?}", Error::ProofOfStake(0))
        );
        assert_eq!(
            "Error::ProofOfStake(255) [65535]",
            &format!("{:?}", Error::ProofOfStake(u8::MAX))
        );
        assert_eq!("Error::User(0) [65536]", &format!("{:?}", Error::User(0)));
        assert_eq!(
            "Error::User(65535) [131071]",
            &format!("{:?}", Error::User(u16::MAX))
        );

        round_trip(Ok(()));
        round_trip(Err(Error::GetURef));
        round_trip(Err(Error::Deserialize));
        round_trip(Err(Error::ContractNotFound));
        round_trip(Err(Error::UnexpectedKeyVariant));
        round_trip(Err(Error::UnexpectedValueVariant));
        round_trip(Err(Error::UnexpectedContractPointerVariant));
        round_trip(Err(Error::Read));
        round_trip(Err(Error::ValueNotFound));
        round_trip(Err(Error::MintFailure));
        round_trip(Err(Error::InvalidPurseName));
        round_trip(Err(Error::InvalidPurse));
        round_trip(Err(Error::MissingArgument));
        round_trip(Err(Error::InvalidArgument));
        round_trip(Err(Error::UpgradeContractAtURef));
        round_trip(Err(Error::Transfer));
        round_trip(Err(Error::NoAccessRights));
        round_trip(Err(Error::None));
        round_trip(Err(Error::ProofOfStake(0)));
        round_trip(Err(Error::ProofOfStake(u8::MAX)));
        round_trip(Err(Error::User(0)));
        round_trip(Err(Error::User(u16::MAX)));
    }
}
