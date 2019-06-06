use crate::alloc::vec::Vec;
use crate::bytesrepr::{Error, FromBytes, ToBytes, U32_SIZE};
use crate::contract_api::pointers::*;
use crate::uref::{URef, UREF_SIZE_SERIALIZED};

const ACCOUNT_ID: u8 = 0;
const HASH_ID: u8 = 1;
const UREF_ID: u8 = 2;
const LOCAL_ID: u8 = 3;

pub const LOCAL_SEED_SIZE: usize = 32;
pub const LOCAL_KEY_HASH_SIZE: usize = 32;

const KEY_ID_SIZE: usize = 1; // u8 used to determine the ID
pub const UREF_SIZE: usize = KEY_ID_SIZE + UREF_SIZE_SERIALIZED;
const LOCAL_SIZE: usize = KEY_ID_SIZE + U32_SIZE + LOCAL_SEED_SIZE + U32_SIZE + LOCAL_KEY_HASH_SIZE;

#[repr(C)]
#[derive(PartialEq, Eq, PartialOrd, Ord, Clone, Copy, Debug, Hash)]
pub enum Key {
    Account([u8; 32]),
    Hash([u8; 32]),
    URef(URef),
    Local {
        seed: [u8; LOCAL_SEED_SIZE],
        key_hash: [u8; LOCAL_KEY_HASH_SIZE],
    },
}

impl Key {
    pub fn to_u_ptr<T>(self) -> Option<UPointer<T>> {
        if let Key::URef(uref) = self {
            UPointer::from_uref(uref).ok()
        } else {
            None
        }
    }

    pub fn to_c_ptr(self) -> Option<ContractPointer> {
        match self {
            Key::URef(uref) => UPointer::from_uref(uref).map(ContractPointer::URef).ok(),
            Key::Hash(id) => Some(ContractPointer::Hash(id)),
            _ => None,
        }
    }

    pub fn normalize(self) -> Key {
        match self {
            Key::URef(uref) => Key::URef(uref.remove_access_rights()),
            other => other,
        }
    }
}

impl ToBytes for Key {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            Key::Account(addr) => {
                let mut result = Vec::with_capacity(37);
                result.push(ACCOUNT_ID);
                result.append(&mut addr.to_bytes()?);
                Ok(result)
            }
            Key::Hash(hash) => {
                let mut result = Vec::with_capacity(37);
                result.push(HASH_ID);
                result.append(&mut hash.to_bytes()?);
                Ok(result)
            }
            Key::URef(uref) => {
                let mut result = Vec::with_capacity(UREF_SIZE);
                result.push(UREF_ID);
                result.append(&mut uref.to_bytes()?);
                Ok(result)
            }
            Key::Local { seed, key_hash } => {
                let mut result = Vec::with_capacity(LOCAL_SIZE);
                result.push(LOCAL_ID);
                result.append(&mut seed.to_bytes()?);
                result.append(&mut key_hash.to_bytes()?);
                Ok(result)
            }
        }
    }
}

impl FromBytes for Key {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            ACCOUNT_ID => {
                let (addr, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                let mut addr_array = [0u8; 32];
                addr_array.copy_from_slice(&addr);
                Ok((Key::Account(addr_array), rem))
            }
            HASH_ID => {
                let (hash, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Key::Hash(hash), rem))
            }
            UREF_ID => {
                let (uref, rem): (URef, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Key::URef(uref), rem))
            }
            LOCAL_ID => {
                let (seed, rest): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                let (key_hash, rest): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Key::Local { seed, key_hash }, rest))
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
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(4 + (size as usize) * UREF_SIZE);
        result.extend(size.to_bytes()?);
        result.extend(
            self.iter()
                .map(ToBytes::to_bytes)
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten(),
        );
        Ok(result)
    }
}
