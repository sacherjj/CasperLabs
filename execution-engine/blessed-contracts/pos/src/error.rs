use core::result;

use cl_std::contract_api;

#[derive(Debug)]
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
    // System errors
    NoCaller,
    // TODO: Put these in their own enum, and wrap them separately in `BondingError` and
    // `UnbondingError`.
    QueueNotStoredAsByteArray,
    QueueDeserializationFailed,
    QueueDeserializationExtraBytes,
    QueueNotFound,
    StakesKeyDeserializationFailed,
    StakesDeserializationFailed,
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
            // System errors
            Error::NoCaller => 0x100,
            Error::QueueNotStoredAsByteArray => 0x200,
            Error::QueueDeserializationFailed => 0x200 + 1,
            Error::QueueDeserializationExtraBytes => 0x200 + 2,
            Error::QueueNotFound => 0x200 + 3,
            Error::StakesKeyDeserializationFailed => 0x300,
            Error::StakesDeserializationFailed => 0x300 + 1,
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
