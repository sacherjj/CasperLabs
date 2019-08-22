use crate::bytesrepr::{Error, FromBytes, ToBytes};
use alloc::vec::Vec;
use num_traits::{FromPrimitive, ToPrimitive};

pub const PHASE_SIZE: usize = 1;

#[derive(Debug, PartialEq, Eq, Clone, Copy, FromPrimitive, ToPrimitive)]
#[repr(u8)]
pub enum Phase {
    Payment = 0,
    Session = 1,
    FinalizePayment = 2,
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
