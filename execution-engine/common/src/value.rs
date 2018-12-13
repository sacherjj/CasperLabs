use super::alloc::string::{self, String};
use super::alloc::vec::Vec;
use super::bytesrepr::{BytesRepr, Error};
use super::key::Key;

#[derive(PartialEq, Eq, Clone, Debug)]
pub enum Value {
    Int32(i32),
    ByteArray(Vec<u8>),
    ListInt32(Vec<i32>),
    String(string::String),
    Acct(Account),
    Contract(Vec<u8>),
}

const INT32_ID: u8 = 0;
const BYTEARRAY_ID: u8 = 1;
const LISTINT32_ID: u8 = 2;
const STRING_ID: u8 = 3;
const ACCT_ID: u8 = 4;
const CONTRACT_ID: u8 = 5;

use self::Value::*;

impl BytesRepr for Value {
    fn to_bytes(&self) -> Vec<u8> {
        match self {
            Int32(i) => {
                let mut result = Vec::with_capacity(5);
                result.push(INT32_ID);
                result.append(&mut i.to_bytes());
                result
            }

            ByteArray(arr) => {
                let mut result = Vec::with_capacity(5 + arr.len());
                result.push(BYTEARRAY_ID);
                result.append(&mut arr.to_bytes());
                result
            }
            ListInt32(arr) => {
                let mut result = Vec::with_capacity(5 + arr.len());
                result.push(LISTINT32_ID);
                result.append(&mut arr.to_bytes());
                result
            }
            String(s) => {
                let mut result = Vec::with_capacity(5 + s.len());
                result.push(STRING_ID);
                result.append(&mut s.to_bytes());
                result
            }
            Acct(a) => {
                let mut result = Vec::new();
                result.push(ACCT_ID);
                result.append(&mut a.to_bytes());
                result
            }
            Contract(arr) => {
                let mut result = Vec::with_capacity(5 + arr.len());
                result.push(CONTRACT_ID);
                result.append(&mut arr.to_bytes());
                result
            }
        }
    }

    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = BytesRepr::from_bytes(bytes)?;
        match id {
            INT32_ID => {
                let (i, rem): (i32, &[u8]) = BytesRepr::from_bytes(rest)?;
                Ok((Int32(i), rem))
            }
            BYTEARRAY_ID => {
                let (arr, rem): (Vec<u8>, &[u8]) = BytesRepr::from_bytes(rest)?;
                Ok((ByteArray(arr), rem))
            }
            LISTINT32_ID => {
                let (arr, rem): (Vec<i32>, &[u8]) = BytesRepr::from_bytes(rest)?;
                Ok((ListInt32(arr), rem))
            }
            STRING_ID => {
                let (s, rem): (String, &[u8]) = BytesRepr::from_bytes(rest)?;
                Ok((String(s), rem))
            }
            ACCT_ID => {
                let (a, rem): (Account, &[u8]) = BytesRepr::from_bytes(rest)?;
                Ok((Acct(a), rem))
            }
            CONTRACT_ID => {
                let (arr, rem): (Vec<u8>, &[u8]) = BytesRepr::from_bytes(rest)?;
                Ok((Contract(arr), rem))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct Account {
    public_key: [u8; 32],
    nonce: u64,
    known_urefs: Vec<Key>,
}

impl BytesRepr for Account {
    fn to_bytes(&self) -> Vec<u8> {
        let mut result = Vec::new();
        result.extend(&self.public_key);
        result.append(&mut self.nonce.to_bytes());
        result.append(&mut self.known_urefs.to_bytes());
        result
    }
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (public_key, rem1): ([u8; 32], &[u8]) = BytesRepr::from_bytes(bytes)?;
        let (nonce, rem2): (u64, &[u8]) = BytesRepr::from_bytes(rem1)?;
        let (known_urefs, rem3): (Vec<Key>, &[u8]) = BytesRepr::from_bytes(rem2)?;
        Ok((
            Account {
                public_key,
                nonce,
                known_urefs,
            },
            rem3,
        ))
    }
}

impl Value {
    pub fn type_string(&self) -> String {
        match self {
            Int32(_) => String::from("Int32"),
            ListInt32(_) => String::from("List[Int32]"),
            String(_) => String::from("String"),
            ByteArray(_) => String::from("ByteArray"),
            Acct(_) => String::from("Account"),
            Contract(_) => String::from("Contract"),
        }
    }

    pub fn as_account(&self) -> &Account {
        match self {
            Acct(a) => a,
            _ => panic!("Not an account: {:?}", self),
        }
    }
}

impl Account {
    pub fn new(public_key: [u8; 32], nonce: u64, known_urefs: Vec<Key>) -> Account {
        Account {
            public_key,
            nonce,
            known_urefs,
        }
    }

    pub fn urefs(&self) -> &[Key] {
        &self.known_urefs
    }
}
