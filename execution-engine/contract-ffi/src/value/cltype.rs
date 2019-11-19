use alloc::{boxed::Box, string::String, vec::Vec};

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
    FixedList(Box<CLType>, usize),
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
    // ...,
    Tuple16([Box<CLType>; 16]),
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

pub struct CLValue {
    cl_type: CLType,
    bytes: Vec<u8>,
}

impl CLValue {
    pub fn as_t<T: CLTyped + FromBytes>(&self) -> Result<T, Error> {
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
    use super::*;
    use crate::{
        bytesrepr::{FromBytes, ToBytes},
        uref::AccessRights,
        value::account::{AssociatedKeys, PublicKey, PurseId, Weight},
    };
    use alloc::{collections::BTreeMap, string::String};

    fn round_trip<T: CLTyped + FromBytes + ToBytes>(t: &T) -> T {
        CLValue::as_t(&CLValue::from_t(t).unwrap()).unwrap()
    }

    #[test]
    fn option_i32_should_work() {
        let x: Option<i32> = Some(7);
        let y: Option<i32> = None;

        let rt_x: Option<i32> = round_trip(&x);
        let rt_y: Option<i32> = round_trip(&y);

        assert_eq!(x, rt_x);
        assert_eq!(y, rt_y);
    }

    #[test]
    fn result_unit_string_should_work() {
        let x: Result<(), String> = Ok(());
        let y: Result<(), String> = Err(String::from("Hello, world!"));

        let rt_x = round_trip(&x);
        let rt_y = round_trip(&y);

        assert_eq!(x, rt_x);
        assert_eq!(y, rt_y);
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

        let rt_x = round_trip(&x);

        assert_eq!(x, rt_x);
    }
}
