pub mod account;
pub mod contract;
pub mod uint;

use crate::bytesrepr::{
    Error, FromBytes, ToBytes, U128_SIZE, U256_SIZE, U32_SIZE, U512_SIZE, U64_SIZE, U8_SIZE,
};
use crate::key::{self, UREF_SIZE};
use crate::uref::URef;
use alloc::string::String;
use alloc::vec::Vec;
use core::convert::TryFrom;
use core::iter;
use core::mem::size_of;

pub use self::account::Account;
pub use self::contract::Contract;
pub use self::uint::{U128, U256, U512};

#[derive(PartialEq, Eq, Clone, Debug)]
pub enum Value {
    Int32(i32),
    UInt64(u64),
    UInt128(U128),
    UInt256(U256),
    UInt512(U512),
    ByteArray(Vec<u8>),
    ListInt32(Vec<i32>),
    String(String),
    ListString(Vec<String>),
    NamedKey(String, key::Key),
    Key(key::Key),
    Account(account::Account),
    Contract(contract::Contract),
    Unit,
}

const INT32_ID: u8 = 0;
const BYTEARRAY_ID: u8 = 1;
const LISTINT32_ID: u8 = 2;
const STRING_ID: u8 = 3;
const ACCT_ID: u8 = 4;
const CONTRACT_ID: u8 = 5;
const NAMEDKEY_ID: u8 = 6;
const LISTSTRING_ID: u8 = 7;
const U128_ID: u8 = 8;
const U256_ID: u8 = 9;
const U512_ID: u8 = 10;
const KEY_ID: u8 = 11;
const UNIT_ID: u8 = 12;
const U64_ID: u8 = 13;

use self::Value::*;

impl ToBytes for Value {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            Int32(i) => {
                let mut result = Vec::with_capacity(U8_SIZE + U32_SIZE);
                result.push(INT32_ID);
                result.append(&mut i.to_bytes()?);
                Ok(result)
            }
            UInt128(u) => {
                let mut result = Vec::with_capacity(U8_SIZE + U128_SIZE);
                result.push(U128_ID);
                result.append(&mut u.to_bytes()?);
                Ok(result)
            }
            UInt256(u) => {
                let mut result = Vec::with_capacity(U8_SIZE + U256_SIZE);
                result.push(U256_ID);
                result.append(&mut u.to_bytes()?);
                Ok(result)
            }
            UInt512(u) => {
                let mut result = Vec::with_capacity(U8_SIZE + U512_SIZE);
                result.push(U512_ID);
                result.append(&mut u.to_bytes()?);
                Ok(result)
            }
            ByteArray(arr) => {
                if arr.len() >= u32::max_value() as usize - U8_SIZE - U32_SIZE {
                    return Err(Error::OutOfMemoryError);
                }
                let mut result = Vec::with_capacity(U8_SIZE + U32_SIZE + arr.len());
                result.push(BYTEARRAY_ID);
                result.append(&mut arr.to_bytes()?);
                Ok(result)
            }
            ListInt32(arr) => {
                if arr.len() * size_of::<i32>() >= u32::max_value() as usize - U8_SIZE - U32_SIZE {
                    return Err(Error::OutOfMemoryError);
                }
                let mut result = Vec::with_capacity(U8_SIZE + U32_SIZE + U32_SIZE * arr.len());
                result.push(LISTINT32_ID);
                result.append(&mut arr.to_bytes()?);
                Ok(result)
            }
            String(s) => {
                if s.len() >= u32::max_value() as usize - U8_SIZE - U32_SIZE {
                    return Err(Error::OutOfMemoryError);
                }
                let size = U8_SIZE + U32_SIZE + s.len();
                let mut result = Vec::with_capacity(size);
                result.push(STRING_ID);
                result.append(&mut s.to_bytes()?);
                Ok(result)
            }
            Account(a) => {
                let mut result = Vec::new();
                result.push(ACCT_ID);
                let mut bytes = a.to_bytes()?;
                if bytes.len() >= u32::max_value() as usize - result.len() {
                    return Err(Error::OutOfMemoryError);
                }
                result.append(&mut bytes);
                Ok(result)
            }
            Contract(c) => Ok(iter::once(CONTRACT_ID).chain(c.to_bytes()?).collect()),
            NamedKey(n, k) => {
                if n.len() + UREF_SIZE >= u32::max_value() as usize - U32_SIZE - U8_SIZE {
                    return Err(Error::OutOfMemoryError);
                }
                let size: usize = U8_SIZE + //size for ID
                  U32_SIZE +                 //size for length of String
                  n.len() +           //size of String
                  UREF_SIZE; //size of urefs
                let mut result = Vec::with_capacity(size);
                result.push(NAMEDKEY_ID);
                result.append(&mut n.to_bytes()?);
                result.append(&mut k.to_bytes()?);
                Ok(result)
            }
            Key(k) => {
                let size: usize = U8_SIZE + UREF_SIZE;
                let mut result = Vec::with_capacity(size);
                result.push(KEY_ID);
                result.append(&mut k.to_bytes()?);
                Ok(result)
            }
            ListString(arr) => {
                let size: usize = U8_SIZE + U32_SIZE + arr.len();
                let mut result = Vec::with_capacity(size);
                result.push(LISTSTRING_ID);
                let bytes = arr.to_bytes()?;
                if bytes.len() >= u32::max_value() as usize - result.len() {
                    return Err(Error::OutOfMemoryError);
                }
                result.append(&mut arr.to_bytes()?);
                Ok(result)
            }
            Unit => Ok(vec![UNIT_ID]),
            UInt64(num) => {
                let mut result = Vec::with_capacity(U8_SIZE + U64_SIZE);
                result.push(U64_ID);
                result.append(&mut num.to_bytes()?);
                Ok(result)
            }
        }
    }
}
impl FromBytes for Value {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            INT32_ID => {
                let (i, rem): (i32, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Int32(i), rem))
            }
            U128_ID => {
                let (u, rem): (U128, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((UInt128(u), rem))
            }
            U256_ID => {
                let (u, rem): (U256, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((UInt256(u), rem))
            }
            U512_ID => {
                let (u, rem): (U512, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((UInt512(u), rem))
            }
            BYTEARRAY_ID => {
                let (arr, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((ByteArray(arr), rem))
            }
            LISTINT32_ID => {
                let (arr, rem): (Vec<i32>, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((ListInt32(arr), rem))
            }
            STRING_ID => {
                let (s, rem): (String, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((String(s), rem))
            }
            ACCT_ID => {
                let (a, rem): (account::Account, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Account(a), rem))
            }
            CONTRACT_ID => {
                let (contract, rem): (contract::Contract, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Contract(contract), rem))
            }
            NAMEDKEY_ID => {
                let (name, rem1): (String, &[u8]) = FromBytes::from_bytes(rest)?;
                let (key, rem2): (key::Key, &[u8]) = FromBytes::from_bytes(rem1)?;
                Ok((NamedKey(name, key), rem2))
            }
            KEY_ID => {
                let (key, rem): (key::Key, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Key(key), rem))
            }
            LISTSTRING_ID => {
                let (arr, rem): (Vec<String>, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((ListString(arr), rem))
            }
            UNIT_ID => Ok((Unit, rest)),
            U64_ID => {
                let (num, rem): (u64, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((UInt64(num), rem))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

impl Value {
    pub fn type_string(&self) -> String {
        match self {
            Int32(_) => String::from("Value::Int32"),
            UInt128(_) => String::from("Value::UInt128"),
            UInt256(_) => String::from("Value::UInt256"),
            UInt512(_) => String::from("Value::UInt512"),
            ListInt32(_) => String::from("Value::List[Int32]"),
            String(_) => String::from("Value::String"),
            ByteArray(_) => String::from("Value::ByteArray"),
            Account(_) => String::from("Value::Account"),
            Contract(_) => String::from("Value::Contract"),
            NamedKey(_, _) => String::from("Value::NamedKey"),
            Key(_) => String::from("Value::Key"),
            ListString(_) => String::from("Value::List[String]"),
            Unit => String::from("Value::Unit"),
            UInt64(_) => String::from("Value::UInt64"),
        }
    }
}

macro_rules! from_try_from_impl {
    ($type:ty, $variant:ident) => {
        impl From<$type> for Value {
            fn from(x: $type) -> Self {
                Value::$variant(x)
            }
        }

        impl TryFrom<Value> for $type {
            type Error = String;

            fn try_from(v: Value) -> Result<$type, String> {
                if let Value::$variant(x) = v {
                    Ok(x)
                } else {
                    Err(v.type_string())
                }
            }
        }
    };
}

from_try_from_impl!(i32, Int32);
from_try_from_impl!(u64, UInt64);
from_try_from_impl!(U128, UInt128);
from_try_from_impl!(U256, UInt256);
from_try_from_impl!(U512, UInt512);
from_try_from_impl!(Vec<u8>, ByteArray);
from_try_from_impl!(Vec<i32>, ListInt32);
from_try_from_impl!(Vec<String>, ListString);
from_try_from_impl!(String, String);
from_try_from_impl!(key::Key, Key);
from_try_from_impl!(account::Account, Account);
from_try_from_impl!(contract::Contract, Contract);

impl From<URef> for Value {
    fn from(uref: URef) -> Self {
        Key(key::Key::URef(uref))
    }
}

impl From<(String, key::Key)> for Value {
    fn from(tuple: (String, key::Key)) -> Self {
        Value::NamedKey(tuple.0, tuple.1)
    }
}

impl TryFrom<Value> for (String, key::Key) {
    type Error = ();

    fn try_from(v: Value) -> Result<(String, key::Key), ()> {
        if let Value::NamedKey(name, key) = v {
            Ok((name, key))
        } else {
            Err(())
        }
    }
}

impl From<()> for Value {
    fn from(_unit: ()) -> Self {
        Value::Unit
    }
}

impl TryFrom<Value> for () {
    type Error = ();

    fn try_from(v: Value) -> Result<(), ()> {
        if let Value::Unit = v {
            Ok(())
        } else {
            Err(())
        }
    }
}
