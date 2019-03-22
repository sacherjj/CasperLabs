use common::key::Key;
use common::value::{Value, U128, U256, U512};
use std::collections::BTreeMap;
use std::fmt;
use std::ops::Add;

#[derive(PartialEq, Eq, Debug, Clone)]
pub struct TypeMismatch {
    pub expected: String,
    pub found: String,
}
impl TypeMismatch {
    pub fn new(expected: String, found: String) -> TypeMismatch {
        TypeMismatch { expected, found }
    }
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum Error {
    TypeMismatch(TypeMismatch),
    Overflow,
}

impl From<TypeMismatch> for Error {
    fn from(t: TypeMismatch) -> Error {
        Error::TypeMismatch(t)
    }
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum Transform {
    Identity,
    Write(Value),
    AddInt32(i32),
    AddUInt128(U128),
    AddUInt256(U256),
    AddUInt512(U512),
    AddKeys(BTreeMap<String, Key>),
    Failure(Error),
}

use self::Transform::*;

macro_rules! i32_checked_addition {
    ($i:expr, $j:expr, $type:ident) => {
        if $j > 0 {
            $i.checked_add($type::from($j)).ok_or(Error::Overflow)
        } else {
            let j_abs = $j.abs();
            $i.checked_sub($type::from(j_abs)).ok_or(Error::Overflow)
        }
    };
}

macro_rules! checked_addition {
    ($i:expr, $v:expr, $variant:ident) => {
        match $v {
            Value::$variant(j) => j.checked_add($i).ok_or(Error::Overflow).map(Value::$variant),
            other => {
                let expected = String::from("$variant");
                Err(TypeMismatch {
                    expected,
                    found: other.type_string(),
                }.into())
            }
        }
    }
}

impl Transform {
    pub fn apply(self, v: Value) -> Result<Value, Error> {
        match self {
            Identity => Ok(v),
            Write(w) => Ok(w),
            AddInt32(i) => match v {
                Value::Int32(j) => j.checked_add(i).ok_or(Error::Overflow).map(Value::Int32),
                Value::UInt128(j) => i32_checked_addition!(j, i, U128).map(Value::UInt128),
                Value::UInt256(j) => i32_checked_addition!(j, i, U256).map(Value::UInt256),
                Value::UInt512(j) => i32_checked_addition!(j, i, U512).map(Value::UInt512),
                other => {
                    let expected = String::from("Int32");
                    Err(TypeMismatch {
                        expected,
                        found: other.type_string(),
                    }.into())
                }
            },
            AddUInt128(i) => checked_addition!(i, v, UInt128),
            AddUInt256(i) => checked_addition!(i, v, UInt256),
            AddUInt512(i) => checked_addition!(i, v, UInt512),
            AddKeys(mut keys) => match v {
                Value::Contract(mut c) => {
                    c.insert_urefs(&mut keys);
                    Ok(c.into())
                }
                Value::Account(mut a) => {
                    a.insert_urefs(&mut keys);
                    Ok(Value::Account(a))
                }
                other => {
                    let expected = String::from("Contract or Account");
                    Err(TypeMismatch {
                        expected,
                        found: other.type_string(),
                    }.into())
                }
            },
            Failure(error) => Err(error),
        }
    }
}

macro_rules! checked_transform_addition {
    ($i:expr, $b:expr, $type:ident, $variant:ident) => {
        match $b {
            AddInt32(j) => i32_checked_addition!($i, j, $type).map_or_else(Failure, $variant),
            $variant(j) => $i.checked_add(j).map_or(Failure(Error::Overflow), $variant),
            other => Failure(TypeMismatch {
                expected: "$variant".to_owned(),
                found: format!("{:?}", other),
            }.into()),
        }
    }
}

impl Add for Transform {
    type Output = Transform;

    fn add(self, other: Transform) -> Transform {
        match (self, other) {
            (a, Identity) => a,
            (Identity, b) => b,
            (a @ Failure(_), _) => a,
            (_, b @ Failure(_)) => b,
            (_, b @ Write(_)) => b,
            (Write(v), b) => {
                // second transform changes value being written
                match b.apply(v) {
                    Err(error) => Failure(error),
                    Ok(new_value) => Write(new_value),
                }
            }
            (AddInt32(i), b) => match b {
                AddInt32(j) => i.checked_add(j).map_or(Failure(Error::Overflow), AddInt32),
                AddUInt256(j) => i32_checked_addition!(j, i, U256).map_or_else(Failure, AddUInt256),
                AddUInt512(j) => i32_checked_addition!(j, i, U512).map_or_else(Failure, AddUInt512),
                other => Failure(TypeMismatch {
                    expected: "AddInt32".to_owned(),
                    found: format!("{:?}", other),
                }.into()),
            },
            (AddUInt128(i), b) => checked_transform_addition!(i, b, U128, AddUInt128),
            (AddUInt256(i), b) => checked_transform_addition!(i, b, U256, AddUInt256),
            (AddUInt512(i), b) => checked_transform_addition!(i, b, U512, AddUInt512),
            (AddKeys(mut ks1), b) => match b {
                AddKeys(mut ks2) => {
                    ks1.append(&mut ks2);
                    AddKeys(ks1)
                }
                other => Failure(TypeMismatch {
                    expected: "AddKeys".to_owned(),
                    found: format!("{:?}", other),
                }.into()),
            },
        }
    }
}

impl fmt::Display for Transform {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}
