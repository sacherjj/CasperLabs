use crate::bytesrepr::{Error, FromBytes, ToBytes};
use alloc::vec::Vec;

const PAYMENT_ID: u8 = 0;
const SESSION_ID: u8 = 1;
const FINALIZE_PAYMENT_ID: u8 = 2;

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Phase {
    Payment,
    Session,
    FinalizePayment,
}

impl ToBytes for Phase {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let id = match self {
            Phase::Payment => PAYMENT_ID,
            Phase::Session => SESSION_ID,
            Phase::FinalizePayment => FINALIZE_PAYMENT_ID,
        };

        Ok(vec![id])
    }
}

impl FromBytes for Phase {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;

        match id {
            PAYMENT_ID => Ok((Phase::Payment, rest)),
            SESSION_ID => Ok((Phase::Session, rest)),
            FINALIZE_PAYMENT_ID => Ok((Phase::FinalizePayment, rest)),
            _ => Err(Error::FormattingError),
        }
    }
}
