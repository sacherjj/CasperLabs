use alloc::{boxed::Box, collections::VecDeque, string::String, vec::Vec};
// Can be removed once https://github.com/rust-lang/rustfmt/issues/3362 is resolved.
#[rustfmt::skip]
use alloc::vec;

use crate::{
    bytesrepr::{self, FromBytes, ToBytes},
    key::Key,
    uref::URef,
    value::{Account, Contract},
};

#[derive(PartialEq, Eq, Clone, Debug)]
pub enum CLType {
    // boolean primitive
    Bool,
    // signed numeric primitives
    I32,
    I64,
    I128,
    I256,
    I512,
    // unsigned numeric primitives
    U8,
    U32,
    U64,
    U128,
    U256,
    U512,
    // unit primitive
    Unit,
    // string primitive
    String,
    // system primitives
    Account,
    Contract,
    Key,
    URef,
    // optional type
    Option(Box<CLType>),
    // list type
    List(Box<CLType>),
    // fixed-length list type (equivalent to rust's array type)
    FixedList(Box<CLType>, u64),
    // result type
    Result {
        ok: Box<CLType>,
        err: Box<CLType>,
    },
    // map type
    Map {
        key: Box<CLType>,
        value: Box<CLType>,
    },
    // tuple types
    Tuple2([Box<CLType>; 2]),
    Tuple3([Box<CLType>; 3]),
    Tuple4([Box<CLType>; 4]),
    Tuple5([Box<CLType>; 5]),
    Tuple6([Box<CLType>; 6]),
    Tuple7([Box<CLType>; 7]),
    Tuple8([Box<CLType>; 8]),
    Tuple9([Box<CLType>; 9]),
    Tuple10([Box<CLType>; 10]),
    Tuple11([Box<CLType>; 11]),
    Tuple12([Box<CLType>; 12]),
    Tuple13([Box<CLType>; 13]),
    Tuple14([Box<CLType>; 14]),
    Tuple15([Box<CLType>; 15]),
    Tuple16([Box<CLType>; 16]),
}

#[repr(u8)]
enum CLTypeTag {
    Bool = 0,
    I32 = 1,
    I64 = 2,
    I128 = 3,
    I256 = 4,
    I512 = 5,
    U8 = 6,
    U32 = 7,
    U64 = 8,
    U128 = 9,
    U256 = 10,
    U512 = 11,
    Unit = 12,
    String = 13,
    Account = 14,
    Contract = 15,
    Key = 16,
    URef = 17,
    Option = 18,
    List = 19,
    FixedList = 20,
    Result = 21,
    Map = 22,
    Tuple2 = 23,
    Tuple3 = 24,
    Tuple4 = 25,
    Tuple5 = 26,
    Tuple6 = 27,
    Tuple7 = 28,
    Tuple8 = 29,
    Tuple9 = 30,
    Tuple10 = 31,
    Tuple11 = 32,
    Tuple12 = 33,
    Tuple13 = 34,
    Tuple14 = 35,
    Tuple15 = 36,
    Tuple16 = 37,
}

impl ToBytes for CLType {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        match self {
            CLType::Bool => Ok(vec![CLTypeTag::Bool as u8]),
            CLType::I32 => Ok(vec![CLTypeTag::I32 as u8]),
            CLType::I64 => Ok(vec![CLTypeTag::I64 as u8]),
            CLType::I128 => Ok(vec![CLTypeTag::I128 as u8]),
            CLType::I256 => Ok(vec![CLTypeTag::I256 as u8]),
            CLType::I512 => Ok(vec![CLTypeTag::I512 as u8]),
            CLType::U8 => Ok(vec![CLTypeTag::U8 as u8]),
            CLType::U32 => Ok(vec![CLTypeTag::U32 as u8]),
            CLType::U64 => Ok(vec![CLTypeTag::U64 as u8]),
            CLType::U128 => Ok(vec![CLTypeTag::U128 as u8]),
            CLType::U256 => Ok(vec![CLTypeTag::U256 as u8]),
            CLType::U512 => Ok(vec![CLTypeTag::U512 as u8]),
            CLType::Unit => Ok(vec![CLTypeTag::Unit as u8]),
            CLType::String => Ok(vec![CLTypeTag::String as u8]),
            CLType::Account => Ok(vec![CLTypeTag::Account as u8]),
            CLType::Contract => Ok(vec![CLTypeTag::Contract as u8]),
            CLType::Key => Ok(vec![CLTypeTag::Key as u8]),
            CLType::URef => Ok(vec![CLTypeTag::URef as u8]),
            CLType::Option(cl_type) => {
                let mut result = vec![CLTypeTag::Option as u8];
                result.append(&mut cl_type.to_bytes()?);
                Ok(result)
            }
            CLType::List(cl_type) => {
                let mut result = vec![CLTypeTag::List as u8];
                result.append(&mut cl_type.to_bytes()?);
                Ok(result)
            }
            CLType::FixedList(cl_type, len) => {
                let mut result = vec![CLTypeTag::FixedList as u8];
                result.append(&mut cl_type.to_bytes()?);
                result.append(&mut len.to_bytes()?);
                Ok(result)
            }
            CLType::Result { ok, err } => {
                let mut result = vec![CLTypeTag::Result as u8];
                result.append(&mut ok.to_bytes()?);
                result.append(&mut err.to_bytes()?);
                Ok(result)
            }
            CLType::Map { key, value } => {
                let mut result = vec![CLTypeTag::Map as u8];
                result.append(&mut key.to_bytes()?);
                result.append(&mut value.to_bytes()?);
                Ok(result)
            }
            CLType::Tuple2(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple2 as u8, cl_type_array)
            }
            CLType::Tuple3(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple3 as u8, cl_type_array)
            }
            CLType::Tuple4(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple4 as u8, cl_type_array)
            }
            CLType::Tuple5(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple5 as u8, cl_type_array)
            }
            CLType::Tuple6(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple6 as u8, cl_type_array)
            }
            CLType::Tuple7(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple7 as u8, cl_type_array)
            }
            CLType::Tuple8(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple8 as u8, cl_type_array)
            }
            CLType::Tuple9(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple9 as u8, cl_type_array)
            }
            CLType::Tuple10(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple10 as u8, cl_type_array)
            }
            CLType::Tuple11(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple11 as u8, cl_type_array)
            }
            CLType::Tuple12(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple12 as u8, cl_type_array)
            }
            CLType::Tuple13(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple13 as u8, cl_type_array)
            }
            CLType::Tuple14(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple14 as u8, cl_type_array)
            }
            CLType::Tuple15(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple15 as u8, cl_type_array)
            }
            CLType::Tuple16(cl_type_array) => {
                serialize_cl_tuple_type(CLTypeTag::Tuple16 as u8, cl_type_array)
            }
        }
    }
}

fn serialize_cl_tuple_type<'a, T: IntoIterator<Item = &'a Box<CLType>>>(
    tag: u8,
    cl_type_array: T,
) -> Result<Vec<u8>, bytesrepr::Error> {
    let mut result = vec![tag];
    for cl_type in cl_type_array {
        result.append(&mut cl_type.to_bytes()?);
    }
    Ok(result)
}

#[allow(clippy::cognitive_complexity)]
impl FromBytes for CLType {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (tag, remainder) = u8::from_bytes(bytes)?;
        if tag == CLTypeTag::Bool as u8 {
            Ok((CLType::Bool, remainder))
        } else if tag == CLTypeTag::I32 as u8 {
            Ok((CLType::I32, remainder))
        } else if tag == CLTypeTag::I64 as u8 {
            Ok((CLType::I64, remainder))
        } else if tag == CLTypeTag::I128 as u8 {
            Ok((CLType::I128, remainder))
        } else if tag == CLTypeTag::I256 as u8 {
            Ok((CLType::I256, remainder))
        } else if tag == CLTypeTag::I512 as u8 {
            Ok((CLType::I512, remainder))
        } else if tag == CLTypeTag::U8 as u8 {
            Ok((CLType::U8, remainder))
        } else if tag == CLTypeTag::U32 as u8 {
            Ok((CLType::U32, remainder))
        } else if tag == CLTypeTag::U64 as u8 {
            Ok((CLType::U64, remainder))
        } else if tag == CLTypeTag::U128 as u8 {
            Ok((CLType::U128, remainder))
        } else if tag == CLTypeTag::U256 as u8 {
            Ok((CLType::U256, remainder))
        } else if tag == CLTypeTag::U512 as u8 {
            Ok((CLType::U512, remainder))
        } else if tag == CLTypeTag::Unit as u8 {
            Ok((CLType::Unit, remainder))
        } else if tag == CLTypeTag::String as u8 {
            Ok((CLType::String, remainder))
        } else if tag == CLTypeTag::Account as u8 {
            Ok((CLType::Account, remainder))
        } else if tag == CLTypeTag::Contract as u8 {
            Ok((CLType::Contract, remainder))
        } else if tag == CLTypeTag::Key as u8 {
            Ok((CLType::Key, remainder))
        } else if tag == CLTypeTag::URef as u8 {
            Ok((CLType::URef, remainder))
        } else if tag == CLTypeTag::Option as u8 {
            let (inner_type, remainder) = CLType::from_bytes(remainder)?;
            let cl_type = CLType::Option(Box::new(inner_type));
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::List as u8 {
            let (inner_type, remainder) = CLType::from_bytes(remainder)?;
            let cl_type = CLType::List(Box::new(inner_type));
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::FixedList as u8 {
            let (inner_type, remainder) = CLType::from_bytes(remainder)?;
            let (len, remainder) = u64::from_bytes(remainder)?;
            let cl_type = CLType::FixedList(Box::new(inner_type), len);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Result as u8 {
            let (ok_type, remainder) = CLType::from_bytes(remainder)?;
            let (err_type, remainder) = CLType::from_bytes(remainder)?;
            let cl_type = CLType::Result {
                ok: Box::new(ok_type),
                err: Box::new(err_type),
            };
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Map as u8 {
            let (key_type, remainder) = CLType::from_bytes(remainder)?;
            let (value_type, remainder) = CLType::from_bytes(remainder)?;
            let cl_type = CLType::Map {
                key: Box::new(key_type),
                value: Box::new(value_type),
            };
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple2 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(2, remainder)?;
            let cl_type = CLType::Tuple2([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple3 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(3, remainder)?;
            let cl_type = CLType::Tuple3([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple4 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(4, remainder)?;
            let cl_type = CLType::Tuple4([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple5 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(5, remainder)?;
            let cl_type = CLType::Tuple5([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple6 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(6, remainder)?;
            let cl_type = CLType::Tuple6([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple7 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(7, remainder)?;
            let cl_type = CLType::Tuple7([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple8 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(8, remainder)?;
            let cl_type = CLType::Tuple8([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple9 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(9, remainder)?;
            let cl_type = CLType::Tuple9([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple10 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(10, remainder)?;
            let cl_type = CLType::Tuple10([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple11 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(11, remainder)?;
            let cl_type = CLType::Tuple11([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple12 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(12, remainder)?;
            let cl_type = CLType::Tuple12([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple13 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(13, remainder)?;
            let cl_type = CLType::Tuple13([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple14 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(14, remainder)?;
            let cl_type = CLType::Tuple14([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple15 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(15, remainder)?;
            let cl_type = CLType::Tuple15([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else if tag == CLTypeTag::Tuple16 as u8 {
            let (mut inner_types, remainder) = parse_cl_tuple_types(16, remainder)?;
            let cl_type = CLType::Tuple16([
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
                inner_types.pop_front().unwrap(),
            ]);
            Ok((cl_type, remainder))
        } else {
            Err(bytesrepr::Error::FormattingError)
        }
    }
}

fn parse_cl_tuple_types(
    count: usize,
    mut bytes: &[u8],
) -> Result<(VecDeque<Box<CLType>>, &[u8]), bytesrepr::Error> {
    let mut cl_types = VecDeque::with_capacity(count);
    for _ in 0..count {
        let (cl_type, remainder) = CLType::from_bytes(bytes)?;
        cl_types.push_back(Box::new(cl_type));
        bytes = remainder;
    }

    Ok((cl_types, bytes))
}

pub trait CLTyped {
    fn cl_type() -> CLType;
}

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

impl CLTyped for i32 {
    fn cl_type() -> CLType {
        CLType::I32
    }
}

impl CLTyped for String {
    fn cl_type() -> CLType {
        CLType::String
    }
}

impl CLTyped for () {
    fn cl_type() -> CLType {
        CLType::Unit
    }
}

// system primitives
impl CLTyped for Account {
    fn cl_type() -> CLType {
        CLType::Account
    }
}

impl CLTyped for Contract {
    fn cl_type() -> CLType {
        CLType::Contract
    }
}

impl CLTyped for Key {
    fn cl_type() -> CLType {
        CLType::Key
    }
}

impl CLTyped for URef {
    fn cl_type() -> CLType {
        CLType::URef
    }
}

impl<T: CLTyped> CLTyped for Option<T> {
    fn cl_type() -> CLType {
        CLType::Option(Box::new(T::cl_type()))
    }
}

impl<T: CLTyped> CLTyped for Vec<T> {
    fn cl_type() -> CLType {
        CLType::List(Box::new(T::cl_type()))
    }
}

impl<T: CLTyped, E: CLTyped> CLTyped for Result<T, E> {
    fn cl_type() -> CLType {
        let ok = Box::new(T::cl_type());
        let err = Box::new(E::cl_type());
        CLType::Result { ok, err }
    }
}

impl<T1: CLTyped, T2: CLTyped> CLTyped for (T1, T2) {
    fn cl_type() -> CLType {
        CLType::Tuple2([Box::new(T1::cl_type()), Box::new(T2::cl_type())])
    }
}

#[cfg(test)]
mod tests {
    use alloc::{collections::BTreeMap, string::String};
    use core::fmt::Debug;

    use super::*;
    use crate::{
        bytesrepr::{FromBytes, ToBytes},
        uref::AccessRights,
        value::account::{AssociatedKeys, PublicKey, PurseId, Weight},
    };

    fn round_trip<T: CLTyped + FromBytes + ToBytes + PartialEq + Debug>(value: &T) {
        let cl_value = CLValue::from_t(value).unwrap();

        let serialized_cl_value = cl_value.to_bytes().unwrap();
        let (parsed_cl_value, remainder) = CLValue::from_bytes(&serialized_cl_value).unwrap();
        assert!(remainder.is_empty());
        assert_eq!(cl_value, parsed_cl_value);

        let parsed_value = CLValue::to_t(&cl_value).unwrap();
        assert_eq!(*value, parsed_value);
    }

    #[test]
    fn option_i32_should_work() {
        let x: Option<i32> = Some(7);
        let y: Option<i32> = None;

        round_trip(&x);
        round_trip(&y);
    }

    #[test]
    fn result_unit_string_should_work() {
        let x: Result<(), String> = Ok(());
        let y: Result<(), String> = Err(String::from("Hello, world!"));

        round_trip(&x);
        round_trip(&y);
    }

    #[test]
    fn result_account_should_work() {
        let named_keys = BTreeMap::new();
        let purse_id = PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE));
        let associated_keys = AssociatedKeys::new(PublicKey::new([0u8; 32]), Weight::new(1));
        let action_thresholds = Default::default();
        let x = Account::new(
            [0u8; 32],
            named_keys,
            purse_id,
            associated_keys,
            action_thresholds,
        );

        round_trip(&x);
    }

    #[test]
    fn tuple_2_should_work() {
        let x = (-1i32, String::from("a"));

        round_trip(&x);
    }
}
