use contract::contract_api::storage;
use proof_of_stake::{Queue, QueueProvider};

const BONDING_KEY: u8 = 1;
const UNBONDING_KEY: u8 = 2;

/// A `QueueProvider` that reads and writes the queue to/from the contract's local state.
pub struct ContractQueue;

impl QueueProvider for ContractQueue {
    /// Reads bonding queue from the local state of the contract.
    fn read_bonding() -> Queue {
        storage::read_local(&BONDING_KEY)
            .unwrap_or_default()
            .unwrap_or_default()
    }

    /// Reads unbonding queue from the local state of the contract.
    fn read_unbonding() -> Queue {
        storage::read_local(&UNBONDING_KEY)
            .unwrap_or_default()
            .unwrap_or_default()
    }

    /// Writes bonding queue to the local state of the contract.
    fn write_bonding(queue: Queue) {
        storage::write_local(BONDING_KEY, queue);
    }

    /// Writes unbonding queue to the local state of the contract.
    fn write_unbonding(queue: Queue) {
        storage::write_local(UNBONDING_KEY, queue);
    }
}
