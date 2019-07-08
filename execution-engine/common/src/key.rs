use core::fmt::Write;

use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;

use crate::alloc::vec::Vec;
use crate::bytesrepr::{Error, FromBytes, ToBytes, N32, U32_SIZE};
use crate::contract_api::pointers::*;
use crate::uref::{URef, UREF_SIZE_SERIALIZED};

const ACCOUNT_ID: u8 = 0;
const HASH_ID: u8 = 1;
const UREF_ID: u8 = 2;
const LOCAL_ID: u8 = 3;

pub const LOCAL_KEY_SIZE: usize = 32;
pub const LOCAL_SEED_SIZE: usize = 32;

const KEY_ID_SIZE: usize = 1; // u8 used to determine the ID
const ACCOUNT_KEY_SIZE: usize = KEY_ID_SIZE + U32_SIZE + N32;
const HASH_KEY_SIZE: usize = KEY_ID_SIZE + U32_SIZE + N32;
pub const UREF_SIZE: usize = KEY_ID_SIZE + UREF_SIZE_SERIALIZED;
const LOCAL_SIZE: usize = KEY_ID_SIZE + U32_SIZE + LOCAL_KEY_SIZE;

/// Creates a 32-byte BLAKE2b hash digest from a given a piece of data
fn hash(bytes: &[u8]) -> [u8; LOCAL_KEY_SIZE] {
    let mut ret = [0u8; LOCAL_KEY_SIZE];
    // Safe to unwrap here because our digest length is constant and valid
    let mut hasher = VarBlake2b::new(LOCAL_KEY_SIZE).unwrap();
    hasher.input(bytes);
    hasher.variable_result(|hash| ret.clone_from_slice(hash));
    ret
}

#[repr(C)]
#[derive(PartialEq, Eq, PartialOrd, Ord, Clone, Copy, Hash)]
pub enum Key {
    Account([u8; 32]),
    Hash([u8; 32]),
    URef(URef),
    Local([u8; LOCAL_KEY_SIZE]),
}

impl Key {
    pub fn local(seed: [u8; LOCAL_SEED_SIZE], key_bytes: &[u8]) -> Self {
        let bytes_to_hash: Vec<u8> = seed.iter().chain(key_bytes.iter()).cloned().collect();
        let hash: [u8; LOCAL_KEY_SIZE] = hash(&bytes_to_hash);
        Key::Local(hash)
    }
}

// There is no impl LowerHex for neither [u8; 32] nor &[u8] in std.
// I can't impl them b/c they're not living in current crate.
/// Creates a hex string from [u8; 32] table.
pub fn addr_to_hex(addr: &[u8; 32]) -> String {
    let mut str = String::with_capacity(64);
    for b in addr {
        write!(&mut str, "{:02x}", b).unwrap();
    }
    str
}

impl core::fmt::Display for Key {
    fn fmt(&self, f: &mut core::fmt::Formatter) -> core::fmt::Result {
        match self {
            Key::Account(addr) => write!(f, "Key::Account({})", addr_to_hex(addr)),
            Key::Hash(addr) => write!(f, "Key::Hash({})", addr_to_hex(addr)),
            Key::URef(uref) => write!(f, "Key::{}", uref), // Display impl for URef will append URef(…).
            Key::Local(hash) => write!(f, "Key::Local({})", addr_to_hex(hash)),
        }
    }
}

impl core::fmt::Debug for Key {
    fn fmt(&self, f: &mut core::fmt::Formatter) -> core::fmt::Result {
        write!(f, "{}", self)
    }
}

use alloc::string::String;

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

    /// Returns bytes of an account
    pub fn as_account(&self) -> Option<[u8; 32]> {
        match self {
            Key::Account(bytes) => Some(*bytes),
            _ => None,
        }
    }

    pub fn normalize(self) -> Key {
        match self {
            Key::URef(uref) => Key::URef(uref.remove_access_rights()),
            other => other,
        }
    }

    pub fn as_uref(&self) -> Option<&URef> {
        match self {
            Key::URef(uref) => Some(uref),
            _ => None,
        }
    }
}

impl From<URef> for Key {
    fn from(uref: URef) -> Key {
        Key::URef(uref)
    }
}

impl ToBytes for Key {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            Key::Account(addr) => {
                let mut result = Vec::with_capacity(ACCOUNT_KEY_SIZE);
                result.push(ACCOUNT_ID);
                result.append(&mut addr.to_bytes()?);
                Ok(result)
            }
            Key::Hash(hash) => {
                let mut result = Vec::with_capacity(HASH_KEY_SIZE);
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
            Key::Local(hash) => {
                let mut result = Vec::with_capacity(LOCAL_SIZE);
                result.push(LOCAL_ID);
                result.append(&mut hash.to_bytes()?);
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
                Ok((Key::Account(addr), rem))
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
                let (hash, rest): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Key::Local(hash), rest))
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

#[allow(clippy::unnecessary_operation)]
#[cfg(test)]
mod tests {
    use crate::key::Key;
    use crate::uref::{AccessRights, URef};
    use alloc::string::String;

    fn test_readable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_readable(), is_true)
    }

    #[test]
    fn test_is_readable() {
        test_readable(AccessRights::READ, true);
        test_readable(AccessRights::READ_ADD, true);
        test_readable(AccessRights::READ_WRITE, true);
        test_readable(AccessRights::READ_ADD_WRITE, true);
        test_readable(AccessRights::ADD, false);
        test_readable(AccessRights::ADD_WRITE, false);
        test_readable(AccessRights::WRITE, false);
    }

    fn test_writable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_writeable(), is_true)
    }

    #[test]
    fn test_is_writable() {
        test_writable(AccessRights::WRITE, true);
        test_writable(AccessRights::READ_WRITE, true);
        test_writable(AccessRights::ADD_WRITE, true);
        test_writable(AccessRights::READ, false);
        test_writable(AccessRights::ADD, false);
        test_writable(AccessRights::READ_ADD, false);
        test_writable(AccessRights::READ_ADD_WRITE, true);
    }

    fn test_addable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_addable(), is_true)
    }

    #[test]
    fn test_is_addable() {
        test_addable(AccessRights::ADD, true);
        test_addable(AccessRights::READ_ADD, true);
        test_addable(AccessRights::READ_WRITE, false);
        test_addable(AccessRights::ADD_WRITE, true);
        test_addable(AccessRights::READ, false);
        test_addable(AccessRights::WRITE, false);
        test_addable(AccessRights::READ_ADD_WRITE, true);
    }

    #[test]
    fn should_display_key() {
        let expected_hash = core::iter::repeat("0").take(64).collect::<String>();
        let addr_array = [0u8; 32];
        let account_key = Key::Account(addr_array);
        assert_eq!(
            format!("{}", account_key),
            format!("Key::Account({})", expected_hash)
        );
        let uref_key = Key::URef(URef::new(addr_array, AccessRights::READ));
        assert_eq!(
            format!("{}", uref_key),
            format!("Key::URef({}, READ)", expected_hash)
        );
        let hash_key = Key::Hash(addr_array);
        assert_eq!(
            format!("{}", hash_key),
            format!("Key::Hash({})", expected_hash)
        );
        let local_key = Key::Local(addr_array);
        assert_eq!(
            format!("{}", local_key),
            format!("Key::Local({})", expected_hash)
        );
    }
}
