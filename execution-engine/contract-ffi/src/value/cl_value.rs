use alloc::vec::Vec;

use crate::{
    bytesrepr::{self, FromBytes, ToBytes},
    value::cl_type::{CLType, CLTyped},
};

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct CLTypeMismatch {
    expected: CLType,
    found: CLType,
}

#[allow(clippy::large_enum_variant)]
#[derive(PartialEq, Eq, Clone, Debug)]
pub enum Error {
    Serialization(bytesrepr::Error),
    Type(CLTypeMismatch),
}

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct CLValue {
    cl_type: CLType,
    bytes: Vec<u8>,
}

impl CLValue {
    pub fn to_t<T: CLTyped + FromBytes>(&self) -> Result<T, Error> {
        let expected = T::cl_type();

        if self.cl_type == expected {
            bytesrepr::deserialize(&self.bytes).map_err(Error::Serialization)
        } else {
            Err(Error::Type(CLTypeMismatch {
                expected,
                found: self.cl_type.clone(),
            }))
        }
    }

    pub fn from_t<T: CLTyped + ToBytes>(t: &T) -> Result<CLValue, Error> {
        let bytes = t.to_bytes().map_err(Error::Serialization)?;

        Ok(CLValue {
            cl_type: T::cl_type(),
            bytes,
        })
    }

    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }
}

impl ToBytes for CLValue {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = self.cl_type.to_bytes()?;
        result.extend(self.bytes.to_bytes()?);
        Ok(result)
    }
}

impl FromBytes for CLValue {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (cl_type, remainder) = CLType::from_bytes(bytes)?;
        let (bytes, remainder) = Vec::<u8>::from_bytes(remainder)?;
        let cl_value = CLValue { cl_type, bytes };
        Ok((cl_value, remainder))
    }
}
