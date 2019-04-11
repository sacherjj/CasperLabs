use super::alloc::vec::Vec;
use super::bytesrepr::{Error, FromBytes, ToBytes, N32, U32_SIZE};
use crate::contract_api::pointers::*;
use core::ops::{BitAnd, BitOr};

#[allow(clippy::derive_hash_xor_eq)]
#[repr(C)]
#[derive(PartialEq, Eq, PartialOrd, Ord, Clone, Copy, Debug, Hash)]

pub enum AccessRights {
    Eqv,
    Read,
    Write,
    Add,
    ReadAdd,
    ReadWrite,
    AddWrite,
    ReadAddWrite,
}

impl BitOr for AccessRights {
    type Output = Self;

    fn bitor(self, rhs: Self) -> Self {
        // It's safe to unwrap because we control which bits are set.
        AccessRights::from_bitwise(self.bitwise_repr() | rhs.bitwise_repr()).unwrap()
    }
}

impl BitAnd for AccessRights {
    type Output = Self;

    fn bitand(self, rhs: Self) -> Self {
        // It's safe to unwrap because we control which bits are set.
        AccessRights::from_bitwise(self.bitwise_repr() & rhs.bitwise_repr()).unwrap()
    }
}

use AccessRights::*;
impl AccessRights {
    pub fn bitwise_repr(&self) -> u8 {
        match self {
            Eqv => 0b0001,
            Read => 0b0011,
            Write => 0b0101,
            Add => 0b1001,
            ReadAdd => 0b1011,
            ReadWrite => 0b0111,
            AddWrite => 0b1101,
            ReadAddWrite => 0b1111,
        }
    }

    pub fn from_bitwise(input: u8) -> Option<AccessRights> {
        match input {
            0b0001 => Some(Eqv),
            0b0011 => Some(Read),
            0b0101 => Some(Write),
            0b1001 => Some(Add),
            0b1011 => Some(ReadAdd),
            0b0111 => Some(ReadWrite),
            0b1101 => Some(AddWrite),
            0b1111 => Some(ReadAddWrite),
            _ => None,
        }
    }

    pub fn is_readable(self) -> bool {
        self & Read == Read
    }

    pub fn is_writeable(self) -> bool {
        self & Write == Write
    }

    pub fn is_addable(self) -> bool {
        self & Add == Add
    }
}

pub const KEY_SIZE: usize = 32;

#[repr(C)]
#[derive(PartialEq, Eq, PartialOrd, Ord, Clone, Copy, Debug, Hash)]
pub enum Key {
    Account([u8; 20]),
    Hash([u8; 32]),
    URef([u8; 32], AccessRights), //TODO: more bytes?
}

use Key::*;
impl Key {
    pub fn to_u_ptr<T>(self) -> Option<UPointer<T>> {
        if let URef(id, access_right) = self {
            Some(UPointer::new(id, access_right))
        } else {
            None
        }
    }

    pub fn to_c_ptr(self) -> Option<ContractPointer> {
        match self {
            URef(id, rights) => Some(ContractPointer::URef(UPointer::new(id, rights))),
            Hash(id) => Some(ContractPointer::Hash(id)),
            _ => None,
        }
    }
}

const ACCOUNT_ID: u8 = 0;
const HASH_ID: u8 = 1;
const UREF_ID: u8 = 2;
const KEY_ID_SIZE: usize = 1; // u8 used to determine the ID
const ACCESS_RIGHTS_SIZE: usize = 1; // u8 used to tag AccessRights
pub const UREF_SIZE: usize = U32_SIZE + N32 + KEY_ID_SIZE + ACCESS_RIGHTS_SIZE;

impl ToBytes for AccessRights {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            AccessRights::Eqv => 1u8.to_bytes(),
            AccessRights::Read => 2u8.to_bytes(),
            AccessRights::Add => 3u8.to_bytes(),
            AccessRights::Write => 4u8.to_bytes(),
            AccessRights::ReadAdd => 5u8.to_bytes(),
            AccessRights::ReadWrite => 6u8.to_bytes(),
            AccessRights::AddWrite => 7u8.to_bytes(),
            AccessRights::ReadAddWrite => 8u8.to_bytes(),
        }
    }
}

impl FromBytes for AccessRights {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        let access_rights = match id {
            1 => Ok(AccessRights::Eqv),
            2 => Ok(AccessRights::Read),
            3 => Ok(AccessRights::Add),
            4 => Ok(AccessRights::Write),
            5 => Ok(AccessRights::ReadAdd),
            6 => Ok(AccessRights::ReadWrite),
            7 => Ok(AccessRights::AddWrite),
            8 => Ok(AccessRights::ReadAddWrite),
            _ => Err(Error::FormattingError),
        };
        access_rights.map(|rights| (rights, rest))
    }
}

impl ToBytes for Key {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            Account(addr) => {
                let mut result = Vec::with_capacity(25);
                result.push(ACCOUNT_ID);
                result.append(&mut (20u32).to_bytes()?);
                result.extend(addr);
                Ok(result)
            }
            Hash(hash) => {
                let mut result = Vec::with_capacity(37);
                result.push(HASH_ID);
                result.append(&mut hash.to_bytes()?);
                Ok(result)
            }
            URef(rf, access_rights) => {
                let mut result = Vec::with_capacity(UREF_SIZE);
                result.push(UREF_ID);
                result.append(&mut rf.to_bytes()?);
                result.append(&mut access_rights.to_bytes()?);
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
                let (access_right, rem2): (AccessRights, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((URef(rf, access_right), rem2))
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

impl AsRef<[u8]> for Key {
    fn as_ref(&self) -> &[u8] {
        match self {
            // TODO: need to distinguish between variants?
            Account(a) => a,
            Hash(h) => h,
            URef(u, ..) => u,
        }
    }
}

#[allow(clippy::unnecessary_operation)]
#[cfg(test)]
mod tests {
    use crate::key::AccessRights::{self, *};

    fn test_readable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_readable(), is_true)
    }

    #[test]
    fn test_is_readable() {
        test_readable(Read, true);
        test_readable(ReadAdd, true);
        test_readable(ReadWrite, true);
        test_readable(ReadAddWrite, true);
        test_readable(Add, false);
        test_readable(AddWrite, false);
        test_readable(Eqv, false);
        test_readable(Write, false);
    }

    fn test_writable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_writeable(), is_true)
    }

    #[test]
    fn test_is_writable() {
        test_writable(Write, true);
        test_writable(ReadWrite, true);
        test_writable(AddWrite, true);
        test_writable(Eqv, false);
        test_writable(Read, false);
        test_writable(Add, false);
        test_writable(ReadAdd, false);
        test_writable(ReadAddWrite, true);
    }

    fn test_addable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_addable(), is_true, "{:?}", right & Add)
    }

    #[test]
    fn test_is_addable() {
        test_addable(Add, true);
        test_addable(ReadAdd, true);
        test_addable(ReadWrite, false);
        test_addable(AddWrite, true);
        test_addable(Eqv, false);
        test_addable(Read, false);
        test_addable(Write, false);
        test_addable(ReadAddWrite, true);
    }
}
