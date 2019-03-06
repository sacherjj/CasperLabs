use super::alloc::vec::Vec;
use super::bytesrepr::{Error, FromBytes, ToBytes};

#[repr(C)]
#[derive(PartialEq, Eq, Clone, Copy, Debug, Hash, PartialOrd, Ord)]
pub enum Key {
    Account([u8; 20]),
    Hash([u8; 32]),
    URef([u8; 32]), //TODO: more bytes?
}

use self::Key::*;

const ACCOUNT_ID: u8 = 0;
const HASH_ID: u8 = 1;
const UREF_ID: u8 = 2;
pub const UREF_SIZE: usize = 37;

impl ToBytes for Key {
    fn to_bytes(&self) -> Vec<u8> {
        match self {
            Account(addr) => {
                let mut result = Vec::with_capacity(25);
                result.push(ACCOUNT_ID);
                result.append(&mut (20u32).to_bytes());
                result.extend(addr);
                result
            }
            Hash(hash) => {
                let mut result = Vec::with_capacity(37);
                result.push(HASH_ID);
                result.append(&mut hash.to_bytes());
                result
            }
            URef(rf) => {
                let mut result = Vec::with_capacity(37);
                result.push(UREF_ID);
                result.append(&mut rf.to_bytes());
                result
            }
        }
    }
}
impl FromBytes for Key {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            ACCOUNT_ID => {
                let (addr, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(rest)?;
                if addr.len() != 20 {
                    Err(Error::FormattingError)
                } else {
                    let mut addr_array = [0u8; 20];
                    addr_array.copy_from_slice(&addr);
                    Ok((Account(addr_array), rem))
                }
            }
            HASH_ID => {
                let (hash, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Hash(hash), rem))
            }
            UREF_ID => {
                let (rf, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((URef(rf), rem))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

impl FromBytes for Vec<Key> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, rest): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<Key> = Vec::with_capacity((size as usize) * UREF_SIZE);
        let mut stream = rest;
        for _ in 0..size {
            let (t, rem): (Key, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}
impl ToBytes for Vec<Key> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(4 + (size as usize) * UREF_SIZE);
        result.extend(size.to_bytes());
        result.extend(self.iter().flat_map(ToBytes::to_bytes));
        result
    }
}

impl AsRef<[u8]> for Key {
    fn as_ref(&self) -> &[u8] {
        match self {
            // TODO: need to distinguish between variants?
            Account(a) => a,
            Hash(h) => h,
            URef(u) => u,
        }
    }
}
