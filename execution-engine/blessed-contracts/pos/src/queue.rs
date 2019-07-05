use alloc::vec::Vec;
use core::convert::TryFrom;
use core::result;

use cl_std::bytesrepr::{self, FromBytes, ToBytes};
use cl_std::contract_api;
use cl_std::value::account::{BlockTime, PublicKey};
use cl_std::value::{Value, U512};

use crate::error::{Error, Result};

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

pub trait QueueProvider {
    /// Reads bonding queue.
    fn read_bonding() -> Queue;

    /// Reads unbonding queue.
    fn read_unbonding() -> Queue;

    /// Writes bonding queue.
    fn write_bonding(queue: &Queue);

    /// Writes unbonding queue.
    fn write_unbonding(queue: &Queue);
}

/// A `QueueProvider` that reads and writes the queue to/from the contract's local state.
pub struct QueueLocal;

impl QueueProvider for QueueLocal {
    /// Reads bonding queue from the local state of the contract.
    fn read_bonding() -> Queue {
        contract_api::read_local(BONDING_KEY).unwrap_or_default()
    }

    /// Reads unbonding queue from the local state of the contract.
    fn read_unbonding() -> Queue {
        contract_api::read_local(UNBONDING_KEY).unwrap_or_default()
    }

    /// Writes bonding queue to the local state of the contract.
    fn write_bonding(queue: &Queue) {
        contract_api::write_local(BONDING_KEY, queue);
    }

    /// Writes unbonding queue to the local state of the contract.
    fn write_unbonding(queue: &Queue) {
        contract_api::write_local(UNBONDING_KEY, queue);
    }
}

/// A queue of bonding or unbonding requests, sorted by timestamp in ascending order.
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

impl TryFrom<Value> for Queue {
    type Error = Error;

    fn try_from(value: Value) -> Result<Self> {
        let bytes = match value {
            Value::ByteArray(bytes) => bytes,
            _ => return Err(Error::QueueNotStoredAsByteArray),
        };
        let (queue, rest) =
            Queue::from_bytes(&bytes).map_err(|_| Error::QueueDeserializationFailed)?;
        if !rest.is_empty() {
            return Err(Error::QueueDeserializationExtraBytes);
        }
        Ok(queue)
    }
}

impl Into<Value> for &Queue {
    fn into(self) -> Value {
        Value::ByteArray(self.to_bytes().expect("Serialization cannot fail"))
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
            bytes.extend(entry.to_bytes()?);
        }
        Ok(bytes)
    }
}

#[cfg(test)]
mod tests {
    use cl_std::value::account::{BlockTime, PublicKey};
    use cl_std::value::U512;

    use crate::error::Error;
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
        assert_eq!(Ok(()), queue.push(val1, U512::from(5), BlockTime(100)));
        assert_eq!(Ok(()), queue.push(val2, U512::from(5), BlockTime(101)));
        assert_eq!(
            Err(Error::MultipleRequests),
            queue.push(val1, U512::from(5), BlockTime(102))
        );
        assert_eq!(
            Err(Error::TimeWentBackwards),
            queue.push(val3, U512::from(5), BlockTime(100))
        );
    }

    #[test]
    fn test_pop_due() {
        let val1 = PublicKey::new(KEY1);
        let val2 = PublicKey::new(KEY2);
        let val3 = PublicKey::new(KEY3);
        let mut queue: Queue = Default::default();
        assert_eq!(Ok(()), queue.push(val1, U512::from(5), BlockTime(100)));
        assert_eq!(Ok(()), queue.push(val2, U512::from(6), BlockTime(101)));
        assert_eq!(Ok(()), queue.push(val3, U512::from(7), BlockTime(102)));
        assert_eq!(
            vec![
                QueueEntry::new(val1, U512::from(5), BlockTime(100)),
                QueueEntry::new(val2, U512::from(6), BlockTime(101)),
            ],
            queue.pop_due(BlockTime(101))
        );
        assert_eq!(
            vec![QueueEntry::new(val3, U512::from(7), BlockTime(102)),],
            queue.pop_due(BlockTime(105))
        );
    }
}
