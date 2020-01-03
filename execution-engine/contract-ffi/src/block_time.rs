//! Home of [`BlockTime`](crate::block_time::BlockTime), an internal type used in the execution of
//! contracts.

use alloc::vec::Vec;

use crate::bytesrepr::{Error, FromBytes, ToBytes, U64_SERIALIZED_LENGTH};

pub const BLOCKTIME_SERIALIZED_LENGTH: usize = U64_SERIALIZED_LENGTH;

#[derive(Clone, Copy, Default, Debug, PartialEq, Eq, PartialOrd)]
pub struct BlockTime(u64);

impl BlockTime {
    pub fn new(value: u64) -> Self {
        BlockTime(value)
    }

    pub fn saturating_sub(self, other: BlockTime) -> Self {
        BlockTime(self.0.saturating_sub(other.0))
    }
}

impl Into<u64> for BlockTime {
    fn into(self) -> u64 {
        self.0
    }
}

impl ToBytes for BlockTime {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        self.0.to_bytes()
    }
}

impl FromBytes for BlockTime {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (time, rem) = FromBytes::from_bytes(bytes)?;
        Ok((BlockTime::new(time), rem))
    }
}
