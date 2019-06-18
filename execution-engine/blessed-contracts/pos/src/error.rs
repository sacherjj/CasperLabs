#[derive(Debug)]
pub enum Error {
    InvalidContractState,
    NotBonded,
    TooManyEventsInQueue,
    CannotUnbondLastValidator,
    SpreadTooHigh,
    MultipleRequests,
    BondTooLarge,
    UnbondTooLarge,
}
