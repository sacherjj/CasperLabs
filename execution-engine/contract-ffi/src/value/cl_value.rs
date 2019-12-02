use alloc::vec::Vec;
use core::u32;

use crate::{
    bytesrepr::{self, FromBytes, ToBytes, U32_SIZE},
    value::cl_type::{CLType, CLTyped},
};

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct CLTypeMismatch {
    pub expected: CLType,
    pub found: CLType,
}

#[allow(clippy::large_enum_variant)]
#[derive(PartialEq, Eq, Clone, Debug)]
pub enum CLValueError {
    Serialization(bytesrepr::Error),
    Type(CLTypeMismatch),
}

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct CLValue {
    cl_type: CLType,
    bytes: Vec<u8>,
}

impl CLValue {
    pub fn from_t<T: CLTyped + ToBytes>(t: &T) -> Result<CLValue, CLValueError> {
        let bytes = t.to_bytes().map_err(CLValueError::Serialization)?;

        Ok(CLValue {
            cl_type: T::cl_type(),
            bytes,
        })
    }

    pub fn to_t<T: CLTyped + FromBytes>(&self) -> Result<T, CLValueError> {
        let expected = T::cl_type();

        if self.cl_type == expected {
            bytesrepr::deserialize(&self.bytes).map_err(CLValueError::Serialization)
        } else {
            Err(CLValueError::Type(CLTypeMismatch {
                expected,
                found: self.cl_type.clone(),
            }))
        }
    }

    // This is only required in order to implement `TryFrom<state::CLValue> for CLValue` (i.e. the
    // conversion from the Protobuf `CLValue`) in a separate module to this one.
    #[doc(hidden)]
    pub fn from_components(cl_type: CLType, bytes: Vec<u8>) -> Self {
        Self { cl_type, bytes }
    }

    // This is only required in order to implement `From<CLValue> for state::CLValue` (i.e. the
    // conversion to the Protobuf `CLValue`) in a separate module to this one.
    #[doc(hidden)]
    pub fn into_components(self) -> (CLType, Vec<u8>) {
        (self.cl_type, self.bytes)
    }

    pub fn cl_type(&self) -> &CLType {
        &self.cl_type
    }

    pub fn serialized_len(&self) -> usize {
        self.cl_type.serialized_len() + U32_SIZE + self.bytes.len()
    }
}

impl ToBytes for CLValue {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let serialized_len = self.serialized_len();
        if serialized_len > u32::max_value() as usize {
            return Err(bytesrepr::Error::OutOfMemoryError);
        }
        let mut result = Vec::with_capacity(serialized_len);
        result.append(&mut self.cl_type.to_bytes()?);
        result.append(&mut self.bytes.to_bytes()?);
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

impl ToBytes for Vec<CLValue> {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let serialized_len = self.iter().map(CLValue::serialized_len).sum();
        if serialized_len > u32::max_value() as usize - U32_SIZE {
            return Err(bytesrepr::Error::OutOfMemoryError);
        }

        let mut result = Vec::with_capacity(serialized_len);
        let len = self.len() as u32;
        result.append(&mut len.to_bytes()?);

        for cl_value in self {
            result.append(&mut cl_value.to_bytes()?);
        }

        Ok(result)
    }
}

impl FromBytes for Vec<CLValue> {
    fn from_bytes(mut bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (len, remainder) = u32::from_bytes(bytes)?;
        bytes = remainder;

        let mut result = Vec::with_capacity(len as usize);
        for _ in 0..len {
            let (cl_value, remainder) = CLValue::from_bytes(bytes)?;
            result.push(cl_value);
            bytes = remainder;
        }
        Ok((result, bytes))
    }
}
