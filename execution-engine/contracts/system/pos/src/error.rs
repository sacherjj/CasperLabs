use core::result;

use contract_ffi::contract_api::{self, Error as ApiError};

#[derive(Debug, PartialEq)]
// TODO: Split this up into user errors vs. system errors.
#[repr(u8)]
pub enum Error {
    NotBonded = 0,
    TooManyEventsInQueue,
    CannotUnbondLastValidator,
    SpreadTooHigh,
    /// Returned when there is another QueueEntry in a Queue, for validator
    /// making a request.
    MultipleRequests,
    BondTooSmall,
    BondTooLarge,
    UnbondTooLarge,
    BondTransferFailed,
    UnbondTransferFailed,
    MissingArgument,
    InvalidArgument,
    // System errors
    TimeWentBackwards,
    StakesNotFound,
    PaymentPurseNotFound,
    PaymentPurseKeyUnexpectedType,
    PaymentPurseBalanceNotFound,
    BondingPurseNotFound,
    BondingPurseKeyUnexpectedType,
    RefundPurseKeyUnexpectedType,
    RewardsPurseNotFound,
    RewardsPurseKeyUnexpectedType,
    // TODO: Put these in their own enum, and wrap them separately in `BondingError` and
    // `UnbondingError`.
    QueueNotStoredAsByteArray,
    QueueDeserializationFailed,
    QueueDeserializationExtraBytes,
    StakesKeyDeserializationFailed,
    StakesDeserializationFailed,
    SystemFunctionCalledByUserAccount,
    InsufficientPaymentForAmountSpent,
    FailedTransferToRewardsPurse,
    FailedTransferToAccountPurse,
    SetRefundPurseCalledOutsidePayment,
}

pub type Result<T> = result::Result<T, Error>;

impl From<Error> for ApiError {
    fn from(error: Error) -> Self {
        ApiError::ProofOfStake(error as u8)
    }
}

pub trait ResultExt<T> {
    fn unwrap_or_revert(self) -> T;
}

impl<T> ResultExt<T> for Result<T> {
    fn unwrap_or_revert(self) -> T {
        self.unwrap_or_else(|err| contract_api::revert(ApiError::from(err)))
    }
}

pub enum PurseLookupError {
    KeyNotFound,
    KeyUnexpectedType,
}

impl PurseLookupError {
    pub fn bonding(err: PurseLookupError) -> Error {
        match err {
            PurseLookupError::KeyNotFound => Error::BondingPurseNotFound,
            PurseLookupError::KeyUnexpectedType => Error::BondingPurseKeyUnexpectedType,
        }
    }

    pub fn payment(err: PurseLookupError) -> Error {
        match err {
            PurseLookupError::KeyNotFound => Error::PaymentPurseNotFound,
            PurseLookupError::KeyUnexpectedType => Error::PaymentPurseKeyUnexpectedType,
        }
    }

    pub fn rewards(err: PurseLookupError) -> Error {
        match err {
            PurseLookupError::KeyNotFound => Error::RewardsPurseNotFound,
            PurseLookupError::KeyUnexpectedType => Error::RewardsPurseKeyUnexpectedType,
        }
    }
}
