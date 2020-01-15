//! Home of [`Phase`](crate::Phase), which represents the phase in which a given contract
//! is executing.

// Can be removed once https://github.com/rust-lang/rustfmt/issues/3362 is resolved.
#[rustfmt::skip]
use alloc::vec;
use alloc::vec::Vec;

use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::{FromPrimitive, ToPrimitive};

use crate::{
    bytesrepr::{Error, FromBytes, ToBytes},
    CLType, CLTyped,
};

pub const PHASE_SERIALIZED_LENGTH: usize = 1;

#[derive(Debug, PartialEq, Eq, Clone, Copy, FromPrimitive, ToPrimitive)]
#[repr(u8)]
pub enum Phase {
    System = 0,
    Payment = 1,
    Session = 2,
    FinalizePayment = 3,
}

impl ToBytes for Phase {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let id = self.to_u8().expect("Phase is represented as a u8");

        Ok(vec![id])
    }
}

impl FromBytes for Phase {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        let phase = FromPrimitive::from_u8(id).ok_or(Error::FormattingError)?;
        Ok((phase, rest))
    }
}

impl CLTyped for Phase {
    fn cl_type() -> CLType {
        CLType::U8
    }
}
