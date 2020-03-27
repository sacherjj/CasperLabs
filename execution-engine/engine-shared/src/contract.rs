use std::collections::BTreeMap;

use types::{
    bytesrepr::{self, Error, FromBytes, ToBytes},
    Key, ProtocolVersion,
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
        let mut result = bytesrepr::allocate_buffer(self)?;
        result.append(&mut self.bytes.to_bytes()?);
        result.append(&mut self.named_keys.to_bytes()?);
        result.append(&mut self.protocol_version.to_bytes()?);
        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        self.bytes.serialized_length()
            + self.named_keys.serialized_length()
            + self.protocol_version.serialized_length()
    }
}

impl FromBytes for Contract {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bytes, rem1) = Vec::<u8>::from_bytes(bytes)?;
        let (named_keys, rem2) = BTreeMap::<String, Key>::from_bytes(rem1)?;
        let (protocol_version, rem3) = ProtocolVersion::from_bytes(rem2)?;
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
