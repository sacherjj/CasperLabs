use std::collections::BTreeMap;
use std::convert::TryFrom;
use std::fmt;
use std::ops::Add;

use contract_ffi::key::Key;
use contract_ffi::value::{Value, U128, U256, U512};
use num::traits::{ToPrimitive, WrappingAdd, WrappingSub};

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
    AddUInt64(u64),
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
from_try_from_impl!(u64, AddUInt64);
from_try_from_impl!(U128, AddUInt128);
from_try_from_impl!(U256, AddUInt256);
from_try_from_impl!(U512, AddUInt512);
from_try_from_impl!(BTreeMap<String, Key>, AddKeys);
from_try_from_impl!(Error, Failure);

/// Attempts to add `j` to `i`
fn i32_wrapping_addition<T>(i: T, j: i32) -> T
where
    T: WrappingAdd + WrappingSub + From<u32>,
{
    if j > 0 {
        // NOTE: This value is greater than 0 so conversion is safe.
        let j_unsigned = j.to_u32().unwrap();
        i.wrapping_add(&j_unsigned.into())
    } else {
        // NOTE: This is is guaranteed to not fail as abs() produces values
        // greater than 0.
        let j_abs = j.abs().to_u32().unwrap();
        i.wrapping_sub(&j_abs.into())
    }
}

fn u64_wrapping_addition(i: u64, j: i32) -> i32 {
    let i32_max_as_u64 = i32::max_value().to_u64().unwrap();
    if i > i32_max_as_u64 {
        let remainder = (i % i32_max_as_u64).to_i32().unwrap();
        j.wrapping_add(remainder)
    } else {
        j.wrapping_add(i.to_i32().unwrap())
    }
}

/// Attempts to add `i` to `v`, assuming `v` is of type `expected`
fn wrapping_addition<T>(i: T, v: Value, expected: &str) -> Result<Value, Error>
where
    T: Into<Value> + TryFrom<Value, Error = String> + WrappingAdd,
{
    match T::try_from(v) {
        Err(v_type) => Err(TypeMismatch {
            expected: String::from(expected),
            found: v_type,
        }
        .into()),

        Ok(j) => Ok(j.wrapping_add(&i).into()),
    }
}

impl Transform {
    pub fn apply(self, v: Value) -> Result<Value, Error> {
        match self {
            Identity => Ok(v),
            Write(w) => Ok(w),
            AddInt32(i) => match v {
                Value::Int32(j) => Ok(Value::Int32(j.wrapping_add(i))),
                Value::UInt64(j) => Ok(Value::UInt64(i32_wrapping_addition(j, i))),
                Value::UInt128(j) => Ok(Value::UInt128(i32_wrapping_addition(j, i))),
                Value::UInt256(j) => Ok(Value::UInt256(i32_wrapping_addition(j, i))),
                Value::UInt512(j) => Ok(Value::UInt512(i32_wrapping_addition(j, i))),
                other => {
                    let expected = String::from("Int32");
                    Err(TypeMismatch {
                        expected,
                        found: other.type_string(),
                    }
                    .into())
                }
            },
            AddUInt64(i) => match v {
                Value::Int32(j) => Ok(Value::Int32(u64_wrapping_addition(i, j))),
                Value::UInt64(j) => Ok(Value::UInt64(i.wrapping_add(j))),
                Value::UInt128(_) => wrapping_addition(i, v, "UInt128"),
                Value::UInt256(_) => wrapping_addition(i, v, "UInt256"),
                Value::UInt512(_) => wrapping_addition(i, v, "UInt512"),
                other => {
                    let expected = String::from("UInt64");
                    Err(TypeMismatch {
                        expected,
                        found: other.type_string(),
                    }
                    .into())
                }
            },
            AddUInt128(i) => wrapping_addition(i, v, "UInt128"),
            AddUInt256(i) => wrapping_addition(i, v, "UInt256"),
            AddUInt512(i) => wrapping_addition(i, v, "UInt512"),
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
/// performing the wrapping addition then wrapping up as a `Transform`
/// again.
fn wrapped_transform_addition<T>(i: T, b: Transform, expected: &str) -> Transform
where
    T: WrappingAdd
        + WrappingSub
        + From<u32>
        + From<u64>
        + Into<Transform>
        + TryFrom<Transform, Error = String>,
{
    if let Transform::AddInt32(j) = b {
        i32_wrapping_addition(i, j).into()
    } else if let Transform::AddUInt64(j) = b {
        i.wrapping_add(&j.into()).into()
    } else {
        match T::try_from(b) {
            Err(b_type) => Failure(
                TypeMismatch {
                    expected: String::from(expected),
                    found: b_type,
                }
                .into(),
            ),

            Ok(j) => i.wrapping_add(&j).into(),
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
                AddInt32(j) => AddInt32(i.wrapping_add(j)),
                AddUInt64(j) => AddUInt64(i32_wrapping_addition(j, i)),
                AddUInt128(j) => AddUInt128(i32_wrapping_addition(j, i)),
                AddUInt256(j) => AddUInt256(i32_wrapping_addition(j, i)),
                AddUInt512(j) => AddUInt512(i32_wrapping_addition(j, i)),
                other => Failure(
                    TypeMismatch {
                        expected: "AddInt32".to_owned(),
                        found: format!("{:?}", other),
                    }
                    .into(),
                ),
            },
            (AddUInt64(i), b) => match b {
                AddInt32(j) => AddInt32(u64_wrapping_addition(i, j)),
                AddUInt64(j) => AddUInt64(i.wrapping_add(j)),
                AddUInt128(j) => AddUInt128(j.wrapping_add(&i.into())),
                AddUInt256(j) => AddUInt256(j.wrapping_add(&i.into())),
                AddUInt512(j) => AddUInt512(j.wrapping_add(&i.into())),
                other => Failure(
                    TypeMismatch {
                        expected: "AddUInt64".to_owned(),
                        found: format!("{:?}", other),
                    }
                    .into(),
                ),
            },
            (AddUInt128(i), b) => wrapped_transform_addition(i, b, "U128"),
            (AddUInt256(i), b) => wrapped_transform_addition(i, b, "U256"),
            (AddUInt512(i), b) => wrapped_transform_addition(i, b, "U512"),
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

pub mod gens {
    use super::Transform;
    use contract_ffi::gens::value_arb;
    use proptest::collection::vec;
    use proptest::prelude::*;

    pub fn transform_arb() -> impl Strategy<Value = Transform> {
        prop_oneof![
            Just(Transform::Identity),
            value_arb().prop_map(Transform::Write),
            any::<i32>().prop_map(Transform::AddInt32),
            any::<u64>().prop_map(Transform::AddUInt64),
            any::<u128>().prop_map(|u| Transform::AddUInt128(u.into())),
            vec(any::<u8>(), 32).prop_map(|u| {
                let mut buf: [u8; 32] = [0u8; 32];
                buf.copy_from_slice(&u);
                Transform::AddUInt256(buf.into())
            }),
            vec(any::<u8>(), 64).prop_map(|u| {
                let mut buf: [u8; 64] = [0u8; 64];
                buf.copy_from_slice(&u);
                Transform::AddUInt512(buf.into())
            }),
        ]
    }
}

#[cfg(test)]
mod tests {
    use num::{Bounded, Num, ToPrimitive};

    use contract_ffi::value::{Value, U128, U256, U512};

    use super::Transform;

    #[test]
    fn i32_overflow() {
        let max = std::i32::MAX;
        let min = std::i32::MIN;

        let apply_overflow = Transform::AddInt32(1).apply(max.into());
        let apply_underflow = Transform::AddInt32(-1).apply(min.into());

        let transform_overflow = Transform::AddInt32(max) + Transform::AddInt32(1);
        let transform_underflow = Transform::AddInt32(min) + Transform::AddInt32(-1);

        assert_eq!(apply_overflow.expect("Unexpected overflow"), min.into());
        assert_eq!(apply_underflow.expect("Unexpected underflow"), max.into());

        assert_eq!(transform_overflow, min.into());
        assert_eq!(transform_underflow, max.into());
    }

    fn uint_overflow_test<T>()
    where
        T: Num + Bounded + Into<Value> + Into<Transform> + Copy,
    {
        let max = T::max_value();
        let min = T::min_value();
        let one = T::one();
        let zero = T::zero();

        let max_value: Value = max.into();
        let max_transform: Transform = max.into();

        let min_value: Value = min.into();
        let min_transform: Transform = min.into();

        let one_transform: Transform = one.into();

        let apply_overflow = Transform::AddInt32(1).apply(max_value.clone());

        let apply_overflow_uint = one_transform.clone().apply(max_value);
        let apply_underflow = Transform::AddInt32(-1).apply(min_value);

        let transform_overflow = max_transform.clone() + Transform::AddInt32(1);
        let transform_overflow_uint = max_transform + one_transform;
        let transform_underflow = min_transform + Transform::AddInt32(-1);

        assert_eq!(apply_overflow, Ok(zero.into()));
        assert_eq!(apply_overflow_uint, Ok(zero.into()));
        assert_eq!(apply_underflow, Ok(max.into()));

        assert_eq!(transform_overflow, zero.into());
        assert_eq!(transform_overflow_uint, zero.into());
        assert_eq!(transform_underflow, max.into());
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

    #[test]
    fn u64_to_i32_addition() {
        let i32_max_as_u64 = i32::max_value().to_u64().unwrap();
        assert_eq!(
            i32::max_value(),
            super::u64_wrapping_addition(i32_max_as_u64, 0)
        );

        // Plus 1 is for "wrapping" the number (going from i32::max to i32::min).
        let base_u64 = (5 * i32_max_as_u64) + 100 + 1;
        assert_eq!(
            i32::min_value() + 100,
            super::u64_wrapping_addition(base_u64, i32::max_value())
        )
    }
}
