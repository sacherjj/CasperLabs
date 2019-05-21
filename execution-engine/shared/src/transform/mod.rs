use std::collections::BTreeMap;
use std::convert::TryFrom;
use std::fmt;
use std::ops::Add;

use common::key::Key;
use common::value::uint::{CheckedAdd, CheckedSub};
use common::value::{Value, U128, U256, U512};

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

/// Error type for applying and combining transforms. A `TypeMismatch`
/// occurs when a transform cannot be applied because the types are
/// not compatible (e.g. trying to add a number to a string). An
/// `Overflow` occurs if addition between numbers would result in the
/// value overflowing its size in memory (e.g. if a, b are i32 and a +
/// b > i32::MAX then a `AddInt32(a).apply(Value::Int32(b))` would
/// cause an overflow).
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

macro_rules! from_try_from_impl {
    ($type:ty, $variant:ident) => {
        impl From<$type> for Transform {
            fn from(x: $type) -> Self {
                Transform::$variant(x)
            }
        }

        impl TryFrom<Transform> for $type {
            type Error = String;

            fn try_from(t: Transform) -> Result<$type, String> {
                match t {
                    Transform::$variant(x) => Ok(x),
                    other => Err(format!("{:?}", other)),
                }
            }
        }
    };
}

use self::Transform::*;

from_try_from_impl!(Value, Write);
from_try_from_impl!(i32, AddInt32);
from_try_from_impl!(U128, AddUInt128);
from_try_from_impl!(U256, AddUInt256);
from_try_from_impl!(U512, AddUInt512);
from_try_from_impl!(BTreeMap<String, Key>, AddKeys);
from_try_from_impl!(Error, Failure);

/// Attempts to add `j` to `i`
fn i32_checked_addition<T>(i: T, j: i32) -> Result<T, Error>
where
    T: CheckedAdd + CheckedSub + From<i32>,
{
    if j > 0 {
        i.checked_add(j.into()).ok_or(Error::Overflow)
    } else {
        let j_abs = j.abs();
        i.checked_sub(j_abs.into()).ok_or(Error::Overflow)
    }
}

/// Attempts to add `i` to `v`, assuming `v` is of type `expected`
fn checked_addition<T>(i: T, v: Value, expected: &str) -> Result<Value, Error>
where
    T: Into<Value> + TryFrom<Value, Error = String> + CheckedAdd,
{
    match T::try_from(v) {
        Err(v_type) => Err(TypeMismatch {
            expected: String::from(expected),
            found: v_type,
        }
        .into()),

        Ok(j) => j.checked_add(i).ok_or(Error::Overflow).map(T::into),
    }
}

impl Transform {
    pub fn apply(self, v: Value) -> Result<Value, Error> {
        match self {
            Identity => Ok(v),
            Write(w) => Ok(w),
            AddInt32(i) => match v {
                Value::Int32(j) => j.checked_add(i).ok_or(Error::Overflow).map(Value::Int32),
                Value::UInt128(j) => i32_checked_addition(j, i).map(Value::UInt128),
                Value::UInt256(j) => i32_checked_addition(j, i).map(Value::UInt256),
                Value::UInt512(j) => i32_checked_addition(j, i).map(Value::UInt512),
                other => {
                    let expected = String::from("Int32");
                    Err(TypeMismatch {
                        expected,
                        found: other.type_string(),
                    }
                    .into())
                }
            },
            AddUInt128(i) => checked_addition(i, v, "UInt128"),
            AddUInt256(i) => checked_addition(i, v, "UInt256"),
            AddUInt512(i) => checked_addition(i, v, "UInt512"),
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
                    }
                    .into())
                }
            },
            Failure(error) => Err(error),
        }
    }
}

/// Combines numeric `Transform`s into a single `Transform`. This is
/// done by unwrapping the `Transform` to obtain the underlying value,
/// performing the checked addition then wrapping up as a `Transform`
/// again.
fn checked_transform_addition<T>(i: T, b: Transform, expected: &str) -> Transform
where
    T: CheckedAdd + CheckedSub + From<i32> + Into<Transform> + TryFrom<Transform, Error = String>,
{
    if let Transform::AddInt32(j) = b {
        i32_checked_addition(i, j).map_or_else(Failure, T::into)
    } else {
        match T::try_from(b) {
            Err(b_type) => Failure(
                TypeMismatch {
                    expected: String::from(expected),
                    found: b_type,
                }
                .into(),
            ),

            Ok(j) => i.checked_add(j).map_or(Failure(Error::Overflow), T::into),
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
                AddUInt256(j) => i32_checked_addition(j, i).map_or_else(Failure, AddUInt256),
                AddUInt512(j) => i32_checked_addition(j, i).map_or_else(Failure, AddUInt512),
                other => Failure(
                    TypeMismatch {
                        expected: "AddInt32".to_owned(),
                        found: format!("{:?}", other),
                    }
                    .into(),
                ),
            },
            (AddUInt128(i), b) => checked_transform_addition(i, b, "U128"),
            (AddUInt256(i), b) => checked_transform_addition(i, b, "U256"),
            (AddUInt512(i), b) => checked_transform_addition(i, b, "U512"),
            (AddKeys(mut ks1), b) => match b {
                AddKeys(mut ks2) => {
                    ks1.append(&mut ks2);
                    AddKeys(ks1)
                }
                other => Failure(
                    TypeMismatch {
                        expected: "AddKeys".to_owned(),
                        found: format!("{:?}", other),
                    }
                    .into(),
                ),
            },
        }
    }
}

impl fmt::Display for Transform {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

#[cfg(test)]
mod tests {
    use super::{Error, Transform};
    use common::value::{Value, U128, U256, U512};
    use num::{Bounded, Num};

    #[test]
    fn i32_overflow() {
        let max = std::i32::MAX;
        let min = std::i32::MIN;

        let apply_overflow = Transform::AddInt32(1).apply(max.into());
        let apply_underflow = Transform::AddInt32(-1).apply(min.into());

        let transform_overflow = Transform::AddInt32(max) + Transform::AddInt32(1);
        let transform_underflow = Transform::AddInt32(min) + Transform::AddInt32(-1);

        assert_eq!(apply_overflow, Err(Error::Overflow));
        assert_eq!(apply_underflow, Err(Error::Overflow));

        assert_eq!(transform_overflow, Transform::Failure(Error::Overflow));
        assert_eq!(transform_underflow, Transform::Failure(Error::Overflow));
    }

    fn uint_overflow_test<T>()
    where
        T: Num + Bounded + Into<Value> + Into<Transform> + Clone,
    {
        let max = T::max_value();
        let min = T::min_value();
        let one = T::one();

        let max_value: Value = max.clone().into();
        let max_transform: Transform = max.into();

        let min_value: Value = min.clone().into();
        let min_transform: Transform = min.into();

        let one_transform: Transform = one.into();

        let apply_overflow = Transform::AddInt32(1).apply(max_value.clone());
        let apply_overflow_uint = one_transform.clone().apply(max_value);
        let apply_underflow = Transform::AddInt32(-1).apply(min_value);

        let transform_overflow = max_transform.clone() + Transform::AddInt32(1);
        let transform_overflow_uint = max_transform + one_transform;
        let transform_underflow = min_transform + Transform::AddInt32(-1);

        assert_eq!(apply_overflow, Err(Error::Overflow));
        assert_eq!(apply_overflow_uint, Err(Error::Overflow));
        assert_eq!(apply_underflow, Err(Error::Overflow));

        assert_eq!(transform_overflow, Transform::Failure(Error::Overflow));
        assert_eq!(transform_overflow_uint, Transform::Failure(Error::Overflow));
        assert_eq!(transform_underflow, Transform::Failure(Error::Overflow));
    }

    #[test]
    fn u128_overflow() {
        uint_overflow_test::<U128>();
    }

    #[test]
    fn u256_overflow() {
        uint_overflow_test::<U256>();
    }

    #[test]
    fn u512_overflow() {
        uint_overflow_test::<U512>();
    }
}
