use crate::bytesrepr::{Error, FromBytes, ToBytes, U32_SIZE, U64_SIZE};
use crate::key::{Key, UREF_SIZE};
use alloc::collections::btree_map::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct Contract {
    bytes: Vec<u8>,
    known_urefs: BTreeMap<String, Key>,
    protocol_version: u64,
}

impl Contract {
    pub fn new(bytes: Vec<u8>, known_urefs: BTreeMap<String, Key>, protocol_version: u64) -> Self {
        Contract {
            bytes,
            known_urefs,
            protocol_version,
        }
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
        if self.bytes.len() + UREF_SIZE * self.known_urefs.len()
            >= u32::max_value() as usize - U32_SIZE * 2
        {
            return Err(Error::OutOfMemoryError);
        }
        let size: usize = U32_SIZE +                           //size for length of bytes
                    self.bytes.len() +                  //size for elements of bytes
                    U32_SIZE +                                 //size for length of known_urefs
                    UREF_SIZE * self.known_urefs.len() + //size for known_urefs elements
                    U64_SIZE; // size for protocol_version

        let mut result = Vec::with_capacity(size);
        result.append(&mut self.bytes.to_bytes()?);
        result.append(&mut self.known_urefs.to_bytes()?);
        result.append(&mut self.protocol_version.to_bytes()?);
        Ok(result)
    }
}

impl FromBytes for Contract {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bytes, rem1): (Vec<u8>, &[u8]) = FromBytes::from_bytes(bytes)?;
        let (known_urefs, rem2): (BTreeMap<String, Key>, &[u8]) = FromBytes::from_bytes(rem1)?;
        let (protocol_version, rem3): (u64, &[u8]) = FromBytes::from_bytes(rem2)?;
        Ok((
            Contract {
                bytes,
                known_urefs,
                protocol_version,
            },
            rem3,
        ))
    }
}
