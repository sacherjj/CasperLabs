use crate::bytesrepr::{Error, FromBytes, ToBytes};
use crate::key::{Key, UREF_SIZE};
use alloc::collections::btree_map::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct Contract {
    bytes: Vec<u8>,
    known_urefs: BTreeMap<String, Key>,
}

impl Contract {
    pub fn new(bytes: Vec<u8>, known_urefs: BTreeMap<String, Key>) -> Self {
        Contract { bytes, known_urefs }
    }

    pub fn insert_urefs(&mut self, keys: &mut BTreeMap<String, Key>) {
        self.known_urefs.append(keys);
    }

    pub fn urefs_lookup(&self) -> &BTreeMap<String, Key> {
        &self.known_urefs
    }

    pub fn destructure(self) -> (Vec<u8>, BTreeMap<String, Key>) {
        (self.bytes, self.known_urefs)
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}

impl ToBytes for Contract {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let size: usize = 4 +                           //size for length of bytes
                    self.bytes.len() +                  //size for elements of bytes
                    4 +                                 //size for length of known_urefs
                    UREF_SIZE * self.known_urefs.len(); //size for known_urefs elements

        let mut result = Vec::with_capacity(size);
        result.append(&mut self.bytes.to_bytes()?);
        result.append(&mut self.known_urefs.to_bytes()?);
        Ok(result)
    }
}

impl FromBytes for Contract {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bytes, rem1): (Vec<u8>, &[u8]) = FromBytes::from_bytes(bytes)?;
        let (known_urefs, rem2): (BTreeMap<String, Key>, &[u8]) = FromBytes::from_bytes(rem1)?;
        Ok((Contract { bytes, known_urefs }, rem2))
    }
}
