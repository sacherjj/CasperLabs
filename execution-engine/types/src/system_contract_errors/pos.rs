//! Home of the Proof of Stake contract's [`Error`] type.

use alloc::vec::Vec;
use core::result;

use crate::{
    bytesrepr::{self, ToBytes, U8_SERIALIZED_LENGTH},
    CLType, CLTyped,
};

/// Errors which can occur while executing the Proof of Stake contract.
// TODO: Split this up into user errors vs. system errors.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
#[repr(u8)]
pub enum Error {
    // ===== User errors =====
    /// The given validator is not bonded.
    NotBonded = 0,
    /// There are too many bonding or unbonding attempts already enqueued to allow more.
    TooManyEventsInQueue,
    /// At least one validator must remain bonded.
    CannotUnbondLastValidator,
    /// Failed to bond or unbond as this would have resulted in exceeding the maximum allowed
    /// difference between the largest and smallest stakes.
    SpreadTooHigh,
    /// The given validator already has a bond or unbond attempt enqueued.
    MultipleRequests,
    /// Attempted to bond with a stake which was too small.
    BondTooSmall,
    /// Attempted to bond with a stake which was too large.
    BondTooLarge,
    /// Attempted to unbond an amount which was too large.
    UnbondTooLarge,
    /// While bonding, the transfer from source purse to the Proof of Stake internal purse failed.
    BondTransferFailed,
    /// While unbonding, the transfer from the Proof of Stake internal purse to the destination
    /// purse failed.
    UnbondTransferFailed,
    // ===== System errors =====
    /// Internal error: a [`BlockTime`](crate::BlockTime) was unexpectedly out of sequence.
    TimeWentBackwards,
    /// Internal error: stakes were unexpectedly empty.
    StakesNotFound,
    /// Internal error: the PoS contract's payment purse wasn't found.
    PaymentPurseNotFound,
    /// Internal error: the PoS contract's payment purse key was the wrong type.
    PaymentPurseKeyUnexpectedType,
    /// Internal error: couldn't retrieve the balance for the PoS contract's payment purse.
    PaymentPurseBalanceNotFound,
    /// Internal error: the PoS contract's bonding purse wasn't found.
    BondingPurseNotFound,
    /// Internal error: the PoS contract's bonding purse key was the wrong type.
    BondingPurseKeyUnexpectedType,
    /// Internal error: the PoS contract's refund purse key was the wrong type.
    RefundPurseKeyUnexpectedType,
    /// Internal error: the PoS contract's rewards purse wasn't found.
    RewardsPurseNotFound,
    /// Internal error: the PoS contract's rewards purse key was the wrong type.
    RewardsPurseKeyUnexpectedType,
    // TODO: Put these in their own enum, and wrap them separately in `BondingError` and
    //       `UnbondingError`.
    /// Internal error: failed to deserialize the stake's key.
    StakesKeyDeserializationFailed,
    /// Internal error: failed to deserialize the stake's balance.
    StakesDeserializationFailed,
    /// The invoked PoS function can only be called by system contracts, but was called by a user
    /// contract.
    SystemFunctionCalledByUserAccount,
    /// Internal error: while finalizing payment, the amount spent exceeded the amount available.
    InsufficientPaymentForAmountSpent,
    /// Internal error: while finalizing payment, failed to pay the validators (the transfer from
    /// the PoS contract's payment purse to rewards purse failed).
    FailedTransferToRewardsPurse,
    /// Internal error: while finalizing payment, failed to refund the caller's purse (the transfer
    /// from the PoS contract's payment purse to refund purse or account's main purse failed).
    FailedTransferToAccountPurse,
    /// PoS contract's "set_refund_purse" method can only be called by the payment code of a
    /// deploy, but was called by the session code.
    SetRefundPurseCalledOutsidePayment,
}

impl CLTyped for Error {
    fn cl_type() -> CLType {
        CLType::U8
    }
}

impl ToBytes for Error {
    fn to_bytes(&self) -> result::Result<Vec<u8>, bytesrepr::Error> {
        let value = *self as u8;
        value.to_bytes()
    }

    fn serialized_length(&self) -> usize {
        U8_SERIALIZED_LENGTH
    }
}

/// An alias for `Result<T, pos::Error>`.
pub type Result<T> = result::Result<T, Error>;

// This error type is not intended to be used by third party crates.
#[doc(hidden)]
pub enum PurseLookupError {
    KeyNotFound,
    KeyUnexpectedType,
}

// This error type is not intended to be used by third party crates.
#[doc(hidden)]
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
