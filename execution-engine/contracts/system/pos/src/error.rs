use core::result;

use cl_std::contract_api;

#[derive(Debug, PartialEq)]
// TODO: Split this up into user errors vs. system errors.
pub enum Error {
    NotBonded,
    TooManyEventsInQueue,
    CannotUnbondLastValidator,
    SpreadTooHigh,
    /// Returned when there is another QueueEntry in a Queue, for validator making a request.
    MultipleRequests,
    BondTooLarge,
    UnbondTooLarge,
    BondTransferFailed,
    UnbondTransferFailed,
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
}

pub type Result<T> = result::Result<T, Error>;

impl Into<u32> for Error {
    fn into(self) -> u32 {
        match self {
            Error::NotBonded => 0,
            Error::TooManyEventsInQueue => 1,
            Error::CannotUnbondLastValidator => 2,
            Error::SpreadTooHigh => 3,
            Error::MultipleRequests => 4,
            Error::BondTooLarge => 5,
            Error::UnbondTooLarge => 6,
            Error::BondTransferFailed => 7,
            Error::UnbondTransferFailed => 8,
            // System errors
            Error::TimeWentBackwards => 256, // 0x100
            Error::StakesNotFound => 257,
            Error::PaymentPurseNotFound => 258,
            Error::PaymentPurseKeyUnexpectedType => 259,
            Error::PaymentPurseBalanceNotFound => 260,
            Error::BondingPurseNotFound => 261,
            Error::BondingPurseKeyUnexpectedType => 262,
            Error::RefundPurseKeyUnexpectedType => 263,
            Error::RewardsPurseNotFound => 264,
            Error::RewardsPurseKeyUnexpectedType => 265,
            Error::QueueNotStoredAsByteArray => 512, // 0x200
            Error::QueueDeserializationFailed => 513,
            Error::QueueDeserializationExtraBytes => 514,
            Error::StakesKeyDeserializationFailed => 768, // 0x300
            Error::StakesDeserializationFailed => 769,
            Error::SystemFunctionCalledByUserAccount => 1024, // 0x400
            Error::InsufficientPaymentForAmountSpent => 1025,
            Error::FailedTransferToRewardsPurse => 1026,
            Error::FailedTransferToAccountPurse => 1027,
        }
    }
}

pub trait ResultExt<T> {
    fn unwrap_or_revert(self) -> T;
}

impl<T> ResultExt<T> for Result<T> {
    fn unwrap_or_revert(self) -> T {
        self.unwrap_or_else(|err| contract_api::revert(err.into()))
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
