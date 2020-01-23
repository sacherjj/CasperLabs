use alloc::{boxed::Box, vec::Vec};
use core::result;

use contract::contract_api::storage;
use types::{
    account::PublicKey,
    bytesrepr::{self, FromBytes, ToBytes},
    system_contract_errors::pos::{Error, Result},
    BlockTime, CLType, CLTyped, U512,
};

const BONDING_KEY: u8 = 1;
const UNBONDING_KEY: u8 = 2;

/// A pending entry in the bonding or unbonding queue.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct QueueEntry {
    /// The validator who is bonding or unbonding.
    pub validator: PublicKey,
    /// The amount by which to change the stakes.
    pub amount: U512,
    /// The timestamp when the request was made.
    pub timestamp: BlockTime,
}

impl QueueEntry {
    /// Creates a new `QueueEntry` with the current block's timestamp.
    fn new(validator: PublicKey, amount: U512, timestamp: BlockTime) -> QueueEntry {
        QueueEntry {
            validator,
            amount,
            timestamp,
        }
    }
}

impl FromBytes for QueueEntry {
    fn from_bytes(bytes: &[u8]) -> result::Result<(Self, &[u8]), bytesrepr::Error> {
        let (validator, bytes) = PublicKey::from_bytes(bytes)?;
        let (amount, bytes) = U512::from_bytes(bytes)?;
        let (timestamp, bytes) = BlockTime::from_bytes(bytes)?;
        let entry = QueueEntry {
            validator,
            amount,
            timestamp,
        };
        Ok((entry, bytes))
    }
}

impl ToBytes for QueueEntry {
    fn to_bytes(&self) -> result::Result<Vec<u8>, bytesrepr::Error> {
        Ok((self.validator.to_bytes()?.into_iter())
            .chain(self.amount.to_bytes()?)
            .chain(self.timestamp.to_bytes()?)
            .collect())
    }
}

impl CLTyped for QueueEntry {
    fn cl_type() -> CLType {
        CLType::Any
    }
}

pub trait QueueProvider {
    /// Reads bonding queue.
    fn read_bonding() -> Queue;

    /// Reads unbonding queue.
    fn read_unbonding() -> Queue;

    /// Writes bonding queue.
    fn write_bonding(queue: Queue);

    /// Writes unbonding queue.
    fn write_unbonding(queue: Queue);
}

/// A `QueueProvider` that reads and writes the queue to/from the contract's
/// local state.
pub struct QueueLocal;

impl QueueProvider for QueueLocal {
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

/// A queue of bonding or unbonding requests, sorted by timestamp in ascending
/// order.
#[derive(Clone, Default)]
pub struct Queue(pub Vec<QueueEntry>);

impl Queue {
    /// Pushes a new entry to the end of the queue.
    ///
    /// Returns an error if the validator already has a request in the queue.
    pub fn push(&mut self, validator: PublicKey, amount: U512, timestamp: BlockTime) -> Result<()> {
        if self.0.iter().any(|entry| entry.validator == validator) {
            return Err(Error::MultipleRequests);
        }
        if let Some(entry) = self.0.last() {
            if entry.timestamp > timestamp {
                return Err(Error::TimeWentBackwards);
            }
        }
        self.0.push(QueueEntry::new(validator, amount, timestamp));
        Ok(())
    }

    /// Returns all queue entries at least as old as the specified timestamp.
    pub fn pop_due(&mut self, timestamp: BlockTime) -> Vec<QueueEntry> {
        let (older_than, rest) = self
            .0
            .iter()
            .partition(|entry| entry.timestamp <= timestamp);
        self.0 = rest;
        older_than
    }
}

impl FromBytes for Queue {
    fn from_bytes(bytes: &[u8]) -> result::Result<(Self, &[u8]), bytesrepr::Error> {
        let (len, mut bytes) = u64::from_bytes(bytes)?;
        let mut queue = Vec::new();
        for _ in 0..len {
            let (entry, rest) = QueueEntry::from_bytes(bytes)?;
            bytes = rest;
            queue.push(entry);
        }
        Ok((Queue(queue), bytes))
    }
}

impl ToBytes for Queue {
    fn to_bytes(&self) -> result::Result<Vec<u8>, bytesrepr::Error> {
        let mut bytes = (self.0.len() as u64).to_bytes()?; // TODO: Allocate correct capacity.
        for entry in &self.0 {
            bytes.append(&mut entry.to_bytes()?);
        }
        Ok(bytes)
    }
}

impl CLTyped for Queue {
    fn cl_type() -> CLType {
        CLType::List(Box::new(QueueEntry::cl_type()))
    }
}

#[cfg(test)]
mod tests {
    use types::{account::PublicKey, system_contract_errors::pos::Error, BlockTime, U512};

    use crate::queue::{Queue, QueueEntry};

    const KEY1: [u8; 32] = [1; 32];
    const KEY2: [u8; 32] = [2; 32];
    const KEY3: [u8; 32] = [3; 32];

    #[test]
    fn test_push() {
        let val1 = PublicKey::new(KEY1);
        let val2 = PublicKey::new(KEY2);
        let val3 = PublicKey::new(KEY3);
        let mut queue: Queue = Default::default();
        assert_eq!(Ok(()), queue.push(val1, U512::from(5), BlockTime::new(100)));
        assert_eq!(Ok(()), queue.push(val2, U512::from(5), BlockTime::new(101)));
        assert_eq!(
            Err(Error::MultipleRequests),
            queue.push(val1, U512::from(5), BlockTime::new(102))
        );
        assert_eq!(
            Err(Error::TimeWentBackwards),
            queue.push(val3, U512::from(5), BlockTime::new(100))
        );
    }

    #[test]
    fn test_pop_due() {
        let val1 = PublicKey::new(KEY1);
        let val2 = PublicKey::new(KEY2);
        let val3 = PublicKey::new(KEY3);
        let mut queue: Queue = Default::default();
        assert_eq!(Ok(()), queue.push(val1, U512::from(5), BlockTime::new(100)));
        assert_eq!(Ok(()), queue.push(val2, U512::from(6), BlockTime::new(101)));
        assert_eq!(Ok(()), queue.push(val3, U512::from(7), BlockTime::new(102)));
        assert_eq!(
            vec![
                QueueEntry::new(val1, U512::from(5), BlockTime::new(100)),
                QueueEntry::new(val2, U512::from(6), BlockTime::new(101)),
            ],
            queue.pop_due(BlockTime::new(101))
        );
        assert_eq!(
            vec![QueueEntry::new(val3, U512::from(7), BlockTime::new(102)),],
            queue.pop_due(BlockTime::new(105))
        );
    }
}
