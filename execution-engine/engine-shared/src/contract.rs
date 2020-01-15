use std::collections::BTreeMap;

use types::{
    bytesrepr::{Error, FromBytes, ToBytes, U32_SERIALIZED_LENGTH, U64_SERIALIZED_LENGTH},
    Key, ProtocolVersion, KEY_UREF_SERIALIZED_LENGTH,
};

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct Contract {
    bytes: Vec<u8>,
    named_keys: BTreeMap<String, Key>,
    protocol_version: ProtocolVersion,
}

impl Contract {
    pub fn new(
        bytes: Vec<u8>,
        named_keys: BTreeMap<String, Key>,
        protocol_version: ProtocolVersion,
    ) -> Self {
        Contract {
            bytes,
            named_keys,
            protocol_version,
        }
    }

    pub fn named_keys_append(&mut self, keys: &mut BTreeMap<String, Key>) {
        self.named_keys.append(keys);
    }

    pub fn named_keys(&self) -> &BTreeMap<String, Key> {
        &self.named_keys
    }

    pub fn named_keys_mut(&mut self) -> &mut BTreeMap<String, Key> {
        &mut self.named_keys
    }

    pub fn destructure(self) -> (Vec<u8>, BTreeMap<String, Key>, ProtocolVersion) {
        (self.bytes, self.named_keys, self.protocol_version)
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn protocol_version(&self) -> ProtocolVersion {
        self.protocol_version
    }

    pub fn take_named_keys(self) -> BTreeMap<String, Key> {
        self.named_keys
    }
}

impl ToBytes for Contract {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        if self.bytes.len()
            + KEY_UREF_SERIALIZED_LENGTH * self.named_keys.len()
            + U64_SERIALIZED_LENGTH
            >= u32::max_value() as usize - U32_SERIALIZED_LENGTH * 2
        {
            return Err(Error::OutOfMemoryError);
        }
        let size: usize = U32_SERIALIZED_LENGTH +                        //size for length of bytes
                    self.bytes.len() +                                   //size for elements of bytes
                    U32_SERIALIZED_LENGTH +                              //size for length of named_keys
                    KEY_UREF_SERIALIZED_LENGTH * self.named_keys.len() + //size for named_keys elements
                    U64_SERIALIZED_LENGTH; //size for protocol_version

        let mut result = Vec::with_capacity(size);
        result.append(&mut self.bytes.to_bytes()?);
        result.append(&mut self.named_keys.to_bytes()?);
        result.append(&mut self.protocol_version.to_bytes()?);
        Ok(result)
    }
}

impl FromBytes for Contract {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bytes, rem1): (Vec<u8>, &[u8]) = FromBytes::from_bytes(bytes)?;
        let (named_keys, rem2): (BTreeMap<String, Key>, &[u8]) = FromBytes::from_bytes(rem1)?;
        let (protocol_version, rem3): (ProtocolVersion, &[u8]) = FromBytes::from_bytes(rem2)?;
        Ok((
            Contract {
                bytes,
                named_keys,
                protocol_version,
            },
            rem3,
        ))
    }
}

pub mod gens {
    use proptest::{collection::vec, prelude::*};

    use types::gens::{named_keys_arb, protocol_version_arb};

    use super::Contract;

    pub fn contract_arb() -> impl Strategy<Value = Contract> {
        protocol_version_arb().prop_flat_map(move |protocol_version_arb| {
            named_keys_arb(20).prop_flat_map(move |urefs| {
                vec(any::<u8>(), 1..1000)
                    .prop_map(move |body| Contract::new(body, urefs.clone(), protocol_version_arb))
            })
        })
    }
}
