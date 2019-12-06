use std::{
    any,
    collections::BTreeMap,
    convert::TryFrom,
    default::Default,
    fmt::{self, Display, Formatter},
    ops::{Add, AddAssign},
};

use num::traits::{AsPrimitive, WrappingAdd};

use contract_ffi::{
    bytesrepr::{self, FromBytes, ToBytes},
    key::Key,
    value::{CLType, CLTyped, CLValue, CLValueError, U128, U256, U512},
};

use crate::stored_value::StoredValue;

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
    Serialization(bytesrepr::Error),
    TypeMismatch(TypeMismatch),
}

impl From<TypeMismatch> for Error {
    fn from(t: TypeMismatch) -> Error {
        Error::TypeMismatch(t)
    }
}

impl From<CLValueError> for Error {
    fn from(cl_value_error: CLValueError) -> Error {
        match cl_value_error {
            CLValueError::Serialization(error) => Error::Serialization(error),
            CLValueError::Type(cl_type_mismatch) => {
                let expected = format!("{:?}", cl_type_mismatch.expected);
                let found = format!("{:?}", cl_type_mismatch.found);
                let type_mismatch = TypeMismatch { expected, found };
                Error::TypeMismatch(type_mismatch)
            }
        }
    }
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum Transform {
    Identity,
    Write(StoredValue),
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

from_try_from_impl!(StoredValue, Write);
from_try_from_impl!(i32, AddInt32);
from_try_from_impl!(u64, AddUInt64);
from_try_from_impl!(U128, AddUInt128);
from_try_from_impl!(U256, AddUInt256);
from_try_from_impl!(U512, AddUInt512);
from_try_from_impl!(BTreeMap<String, Key>, AddKeys);
from_try_from_impl!(Error, Failure);

/// Attempts a wrapping addition of `to_add` to `stored_value`, assuming `stored_value` is
/// compatible with type `Y`.
fn wrapping_addition<Y>(stored_value: StoredValue, to_add: Y) -> Result<StoredValue, Error>
where
    Y: AsPrimitive<i32>
        + AsPrimitive<i64>
        + AsPrimitive<u8>
        + AsPrimitive<u32>
        + AsPrimitive<u64>
        + AsPrimitive<U128>
        + AsPrimitive<U256>
        + AsPrimitive<U512>,
{
    let expected_type_name = stored_value.type_name();
    let cl_value = stored_value
        .as_cl_value()
        .ok_or_else(|| TypeMismatch::new("CLValue".to_string(), expected_type_name))?
        .clone();

    match cl_value.cl_type() {
        CLType::I32 => do_wrapping_addition::<i32, _>(cl_value, to_add),
        CLType::I64 => do_wrapping_addition::<i64, _>(cl_value, to_add),
        CLType::U8 => do_wrapping_addition::<u8, _>(cl_value, to_add),
        CLType::U32 => do_wrapping_addition::<u32, _>(cl_value, to_add),
        CLType::U64 => do_wrapping_addition::<u64, _>(cl_value, to_add),
        CLType::U128 => do_wrapping_addition::<U128, _>(cl_value, to_add),
        CLType::U256 => do_wrapping_addition::<U256, _>(cl_value, to_add),
        CLType::U512 => do_wrapping_addition::<U512, _>(cl_value, to_add),
        other => {
            let expected = format!("integral type compatible with {}", any::type_name::<Y>());
            let found = format!("{:?}", other);
            Err(TypeMismatch::new(expected, found).into())
        }
    }
}

/// Attempts a wrapping addition of `to_add` to the value represented by `cl_value`.
fn do_wrapping_addition<X, Y>(cl_value: CLValue, to_add: Y) -> Result<StoredValue, Error>
where
    X: WrappingAdd + CLTyped + ToBytes + FromBytes + Copy + 'static,
    Y: AsPrimitive<X>,
{
    let x: X = cl_value.into_t()?;
    let result = x.wrapping_add(&(to_add.as_()));
    Ok(StoredValue::CLValue(CLValue::from_t(&result)?))
}

impl Transform {
    pub fn apply(self, stored_value: StoredValue) -> Result<StoredValue, Error> {
        match self {
            Transform::Identity => Ok(stored_value),
            Transform::Write(new_value) => Ok(new_value),
            Transform::AddInt32(to_add) => wrapping_addition(stored_value, to_add),
            Transform::AddUInt64(to_add) => wrapping_addition(stored_value, to_add),
            Transform::AddUInt128(to_add) => wrapping_addition(stored_value, to_add),
            Transform::AddUInt256(to_add) => wrapping_addition(stored_value, to_add),
            Transform::AddUInt512(to_add) => wrapping_addition(stored_value, to_add),
            Transform::AddKeys(mut keys) => match stored_value {
                StoredValue::Contract(mut contract) => {
                    contract.named_keys_append(&mut keys);
                    Ok(StoredValue::Contract(contract))
                }
                StoredValue::Account(mut account) => {
                    account.named_keys_append(&mut keys);
                    Ok(StoredValue::Account(account))
                }
                StoredValue::CLValue(cl_value) => {
                    let expected = "Contract or Account".to_string();
                    let found = format!("{:?}", cl_value.cl_type());
                    Err(TypeMismatch::new(expected, found).into())
                }
            },
            Transform::Failure(error) => Err(error),
        }
    }
}

/// Combines numeric `Transform`s into a single `Transform`. This is done by unwrapping the
/// `Transform` to obtain the underlying value, performing the wrapping addition then wrapping up as
/// a `Transform` again.
fn wrapped_transform_addition<T>(i: T, b: Transform, expected: &str) -> Transform
where
    T: WrappingAdd
        + AsPrimitive<i32>
        + From<u32>
        + From<u64>
        + Into<Transform>
        + TryFrom<Transform, Error = String>,
    i32: AsPrimitive<T>,
{
    if let Transform::AddInt32(j) = b {
        i.wrapping_add(&j.as_()).into()
    } else if let Transform::AddUInt64(j) = b {
        i.wrapping_add(&j.into()).into()
    } else {
        match T::try_from(b) {
            Err(b_type) => Transform::Failure(
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
            (a, Transform::Identity) => a,
            (Transform::Identity, b) => b,
            (a @ Transform::Failure(_), _) => a,
            (_, b @ Transform::Failure(_)) => b,
            (_, b @ Transform::Write(_)) => b,
            (Transform::Write(v), b) => {
                // second transform changes value being written
                match b.apply(v) {
                    Err(error) => Transform::Failure(error),
                    Ok(new_value) => Transform::Write(new_value),
                }
            }
            (Transform::AddInt32(i), b) => match b {
                Transform::AddInt32(j) => Transform::AddInt32(i.wrapping_add(j)),
                Transform::AddUInt64(j) => Transform::AddUInt64(j.wrapping_add(i as u64)),
                Transform::AddUInt128(j) => Transform::AddUInt128(j.wrapping_add(&(i.as_()))),
                Transform::AddUInt256(j) => Transform::AddUInt256(j.wrapping_add(&(i.as_()))),
                Transform::AddUInt512(j) => Transform::AddUInt512(j.wrapping_add(&i.as_())),
                other => Transform::Failure(
                    TypeMismatch::new("AddInt32".to_owned(), format!("{:?}", other)).into(),
                ),
            },
            (Transform::AddUInt64(i), b) => match b {
                Transform::AddInt32(j) => Transform::AddInt32(j.wrapping_add(i as i32)),
                Transform::AddUInt64(j) => Transform::AddUInt64(i.wrapping_add(j)),
                Transform::AddUInt128(j) => Transform::AddUInt128(j.wrapping_add(&i.into())),
                Transform::AddUInt256(j) => Transform::AddUInt256(j.wrapping_add(&i.into())),
                Transform::AddUInt512(j) => Transform::AddUInt512(j.wrapping_add(&i.into())),
                other => Transform::Failure(
                    TypeMismatch::new("AddUInt64".to_owned(), format!("{:?}", other)).into(),
                ),
            },
            (Transform::AddUInt128(i), b) => wrapped_transform_addition(i, b, "U128"),
            (Transform::AddUInt256(i), b) => wrapped_transform_addition(i, b, "U256"),
            (Transform::AddUInt512(i), b) => wrapped_transform_addition(i, b, "U512"),
            (Transform::AddKeys(mut ks1), b) => match b {
                Transform::AddKeys(mut ks2) => {
                    ks1.append(&mut ks2);
                    Transform::AddKeys(ks1)
                }
                other => Transform::Failure(
                    TypeMismatch::new("AddKeys".to_owned(), format!("{:?}", other)).into(),
                ),
            },
        }
    }
}

impl AddAssign for Transform {
    fn add_assign(&mut self, other: Self) {
        *self = self.clone() + other;
    }
}

impl Display for Transform {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl Default for Transform {
    fn default() -> Self {
        Transform::Identity
    }
}

pub mod gens {
    use super::Transform;
    use crate::stored_value::gens::stored_value_arb;
    use proptest::{collection::vec, prelude::*};

    pub fn transform_arb() -> impl Strategy<Value = Transform> {
        prop_oneof![
            Just(Transform::Identity),
            stored_value_arb().prop_map(Transform::Write),
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
    use num::{Bounded, Num};

    use contract_ffi::{
        uref::{AccessRights, URef},
        value::{account::PurseId, ProtocolVersion, U128, U256, U512},
    };

    use super::*;
    use crate::{
        account::{Account, ActionThresholds, AssociatedKeys},
        contract::Contract,
    };

    #[test]
    fn i32_overflow() {
        let max = std::i32::MAX;
        let min = std::i32::MIN;

        let max_value = StoredValue::CLValue(CLValue::from_t(&max).unwrap());
        let min_value = StoredValue::CLValue(CLValue::from_t(&min).unwrap());

        let apply_overflow = Transform::AddInt32(1).apply(max_value.clone());
        let apply_underflow = Transform::AddInt32(-1).apply(min_value.clone());

        let transform_overflow = Transform::AddInt32(max) + Transform::AddInt32(1);
        let transform_underflow = Transform::AddInt32(min) + Transform::AddInt32(-1);

        assert_eq!(apply_overflow.expect("Unexpected overflow"), min_value);
        assert_eq!(apply_underflow.expect("Unexpected underflow"), max_value);

        assert_eq!(transform_overflow, min.into());
        assert_eq!(transform_underflow, max.into());
    }

    fn uint_overflow_test<T>()
    where
        T: Num + Bounded + CLTyped + ToBytes + Into<Transform> + Copy,
    {
        let max = T::max_value();
        let min = T::min_value();
        let one = T::one();
        let zero = T::zero();

        let max_value = StoredValue::CLValue(CLValue::from_t(&max).unwrap());
        let min_value = StoredValue::CLValue(CLValue::from_t(&min).unwrap());
        let zero_value = StoredValue::CLValue(CLValue::from_t(&zero).unwrap());

        let max_transform: Transform = max.into();
        let min_transform: Transform = min.into();

        let one_transform: Transform = one.into();

        let apply_overflow = Transform::AddInt32(1).apply(max_value.clone());

        let apply_overflow_uint = one_transform.clone().apply(max_value.clone());
        let apply_underflow = Transform::AddInt32(-1).apply(min_value.clone());

        let transform_overflow = max_transform.clone() + Transform::AddInt32(1);
        let transform_overflow_uint = max_transform + one_transform;
        let transform_underflow = min_transform + Transform::AddInt32(-1);

        assert_eq!(apply_overflow, Ok(zero_value.clone()));
        assert_eq!(apply_overflow_uint, Ok(zero_value));
        assert_eq!(apply_underflow, Ok(max_value));

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
    fn wrapping_addition_should_fail() {
        fn assert_yields_type_mismatch_error(stored_value: StoredValue) {
            match wrapping_addition(stored_value, 0_i32) {
                Err(Error::TypeMismatch(_)) => (),
                _ => panic!("wrapping addition should yield TypeMismatch error"),
            };
        }

        let contract = StoredValue::Contract(Contract::new(
            vec![],
            BTreeMap::new(),
            ProtocolVersion::default(),
        ));
        assert_yields_type_mismatch_error(contract);

        let uref = URef::new([0; 32], AccessRights::READ);
        let account = StoredValue::Account(Account::new(
            [0; 32],
            BTreeMap::new(),
            PurseId::new(uref),
            AssociatedKeys::default(),
            ActionThresholds::default(),
        ));
        assert_yields_type_mismatch_error(account);

        let cl_bool = StoredValue::CLValue(CLValue::from_t(&true).expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_bool);

        let cl_unit = StoredValue::CLValue(CLValue::from_t(&()).expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_unit);

        let cl_string = StoredValue::CLValue(CLValue::from_t(&"a").expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_string);

        let cl_key = StoredValue::CLValue(
            CLValue::from_t(&Key::Hash([0; 32])).expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_key);

        let cl_uref = StoredValue::CLValue(CLValue::from_t(&uref).expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_uref);

        let cl_option =
            StoredValue::CLValue(CLValue::from_t(&Some(0_u8)).expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_option);

        let cl_list =
            StoredValue::CLValue(CLValue::from_t(&vec![0_u8]).expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_list);

        let cl_fixed_list =
            StoredValue::CLValue(CLValue::from_t(&[0_u8]).expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_fixed_list);

        let cl_result = StoredValue::CLValue(
            CLValue::from_t(&Result::<(), _>::Err(0_u8)).expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_result);

        let cl_map = StoredValue::CLValue(
            CLValue::from_t(&BTreeMap::<u8, u8>::new()).expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_map);

        let cl_tuple1 =
            StoredValue::CLValue(CLValue::from_t(&(0_u8,)).expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_tuple1);

        let cl_tuple2 =
            StoredValue::CLValue(CLValue::from_t(&(0_u8, 0_u8)).expect("should create CLValue"));
        assert_yields_type_mismatch_error(cl_tuple2);

        let cl_tuple3 = StoredValue::CLValue(
            CLValue::from_t(&(0_u8, 0_u8, 0_u8)).expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_tuple3);

        let cl_tuple4 = StoredValue::CLValue(
            CLValue::from_t(&(0_u8, 0_u8, 0_u8, 0_u8)).expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_tuple4);

        let cl_tuple5 = StoredValue::CLValue(
            CLValue::from_t(&(0_u8, 0_u8, 0_u8, 0_u8, 0_u8)).expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_tuple5);

        let cl_tuple6 = StoredValue::CLValue(
            CLValue::from_t(&(0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8)).expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_tuple6);

        let cl_tuple7 = StoredValue::CLValue(
            CLValue::from_t(&(0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8))
                .expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_tuple7);

        let cl_tuple8 = StoredValue::CLValue(
            CLValue::from_t(&(0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8))
                .expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_tuple8);

        let cl_tuple9 = StoredValue::CLValue(
            CLValue::from_t(&(0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8))
                .expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_tuple9);

        let cl_tuple10 = StoredValue::CLValue(
            CLValue::from_t(&(0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8, 0_u8))
                .expect("should create CLValue"),
        );
        assert_yields_type_mismatch_error(cl_tuple10);
    }
}

#[test]
#[allow(clippy::cognitive_complexity)]
fn wrapping_addition_should_succeed() {
    fn add<X, Y>(current_value: X, to_add: Y) -> X
    where
        X: CLTyped + ToBytes + FromBytes + PartialEq + fmt::Debug,
        Y: AsPrimitive<i32>
            + AsPrimitive<i64>
            + AsPrimitive<u8>
            + AsPrimitive<u32>
            + AsPrimitive<u64>
            + AsPrimitive<U128>
            + AsPrimitive<U256>
            + AsPrimitive<U512>,
    {
        let current =
            StoredValue::CLValue(CLValue::from_t(&current_value).expect("should create CLValue"));
        let result = wrapping_addition(current, to_add).expect("wrapping addition should succeed");
        CLValue::try_from(result)
            .expect("should be CLValue")
            .into_t()
            .expect("should parse to X")
    }

    // Adding to i32
    assert_eq!(1, add(0_i32, 1_i32));
    assert_eq!(i32::min_value(), add(i32::max_value(), 1_i32));
    assert_eq!(-2, add(i32::max_value(), i32::max_value()));
    assert_eq!(0, add(1_i32, -1_i32));
    assert_eq!(-1, add(0, -1_i32));
    assert_eq!(i32::max_value(), add(-1_i32, i32::min_value()));

    assert_eq!(1, add(0_i32, 1_u64));
    assert_eq!(i32::min_value(), add(i32::max_value(), 1_u64));
    assert_eq!(-2, add(i32::max_value(), i32::max_value() as u64));

    assert_eq!(1, add(0_i32, U128::from(1)));
    assert_eq!(i32::min_value(), add(i32::max_value(), U128::from(1)));
    assert_eq!(-2, add(i32::max_value(), U128::from(i32::max_value())));

    assert_eq!(1, add(0_i32, U256::from(1)));
    assert_eq!(i32::min_value(), add(i32::max_value(), U256::from(1)));
    assert_eq!(-2, add(i32::max_value(), U256::from(i32::max_value())));

    assert_eq!(1, add(0_i32, U512::from(1)));
    assert_eq!(i32::min_value(), add(i32::max_value(), U512::from(1)));
    assert_eq!(-2, add(i32::max_value(), U512::from(i32::max_value())));

    // Adding to i64
    assert_eq!(1, add(0_i64, 1_i32));
    assert_eq!(i64::min_value(), add(i64::max_value(), 1_i32));
    assert_eq!(0, add(1_i32, -1_i32));
    assert_eq!(-1, add(0, -1_i32));
    assert_eq!(i64::max_value(), add(i64::min_value(), -1_i32));

    assert_eq!(1, add(0_i64, 1_u64));
    assert_eq!(i64::min_value(), add(i64::max_value(), 1_u64));
    assert_eq!(-2, add(i64::max_value(), i64::max_value() as u64));

    assert_eq!(1, add(0_i64, U128::from(1)));
    assert_eq!(i64::min_value(), add(i64::max_value(), U128::from(1)));
    assert_eq!(-2, add(i64::max_value(), U128::from(i64::max_value())));

    assert_eq!(1, add(0_i64, U256::from(1)));
    assert_eq!(i64::min_value(), add(i64::max_value(), U256::from(1)));
    assert_eq!(-2, add(i64::max_value(), U256::from(i64::max_value())));

    assert_eq!(1, add(0_i64, U512::from(1)));
    assert_eq!(i64::min_value(), add(i64::max_value(), U512::from(1)));
    assert_eq!(-2, add(i64::max_value(), U512::from(i64::max_value())));

    // Adding to u8
    assert_eq!(1, add(0_u8, 1_i32));
    assert_eq!(0, add(u8::max_value(), 1_i32));
    assert_eq!(u8::max_value(), add(u8::max_value(), 256_i32));
    assert_eq!(0, add(u8::max_value(), 257_i32));
    assert_eq!(0, add(1_u8, -1_i32));
    assert_eq!(u8::max_value(), add(0, -1_i32));
    assert_eq!(0, add(0_u8, -256_i32));
    assert_eq!(u8::max_value(), add(0, -257_i32));
    assert_eq!(u8::max_value(), add(0, i32::max_value()));
    assert_eq!(0, add(0_u8, i32::min_value()));

    assert_eq!(1, add(0_u8, 1_u64));
    assert_eq!(0, add(u8::max_value(), 1_u64));
    assert_eq!(1, add(0_u8, u64::from(u8::max_value()) + 2));
    assert_eq!(u8::max_value(), add(0, u64::max_value()));

    assert_eq!(1, add(0_u8, U128::from(1)));
    assert_eq!(0, add(u8::max_value(), U128::from(1)));
    assert_eq!(1, add(0_u8, U128::from(u8::max_value()) + 2));
    assert_eq!(u8::max_value(), add(0, U128::max_value()));

    assert_eq!(1, add(0_u8, U256::from(1)));
    assert_eq!(0, add(u8::max_value(), U256::from(1)));
    assert_eq!(1, add(0_u8, U256::from(u8::max_value()) + 2));
    assert_eq!(u8::max_value(), add(0, U256::max_value()));

    assert_eq!(1, add(0_u8, U512::from(1)));
    assert_eq!(0, add(u8::max_value(), U512::from(1)));
    assert_eq!(1, add(0_u8, U512::from(u8::max_value()) + 2));
    assert_eq!(u8::max_value(), add(0, U512::max_value()));

    // Adding to u32
    assert_eq!(1, add(0_u32, 1_i32));
    assert_eq!(0, add(u32::max_value(), 1_i32));
    assert_eq!(0, add(1_u32, -1_i32));
    assert_eq!(u32::max_value(), add(0, -1_i32));
    assert_eq!(i32::max_value() as u32 + 1, add(0, i32::min_value()));

    assert_eq!(1, add(0_u32, 1_u64));
    assert_eq!(0, add(u32::max_value(), 1_u64));
    assert_eq!(1, add(0_u32, u64::from(u32::max_value()) + 2));
    assert_eq!(u32::max_value(), add(0, u64::max_value()));

    assert_eq!(1, add(0_u32, U128::from(1)));
    assert_eq!(0, add(u32::max_value(), U128::from(1)));
    assert_eq!(1, add(0_u32, U128::from(u32::max_value()) + 2));
    assert_eq!(u32::max_value(), add(0, U128::max_value()));

    assert_eq!(1, add(0_u32, U256::from(1)));
    assert_eq!(0, add(u32::max_value(), U256::from(1)));
    assert_eq!(1, add(0_u32, U256::from(u32::max_value()) + 2));
    assert_eq!(u32::max_value(), add(0, U256::max_value()));

    assert_eq!(1, add(0_u32, U512::from(1)));
    assert_eq!(0, add(u32::max_value(), U512::from(1)));
    assert_eq!(1, add(0_u32, U512::from(u32::max_value()) + 2));
    assert_eq!(u32::max_value(), add(0, U512::max_value()));

    // Adding to u64
    assert_eq!(1, add(0_u64, 1_i32));
    assert_eq!(0, add(u64::max_value(), 1_i32));
    assert_eq!(0, add(1_u64, -1_i32));
    assert_eq!(u64::max_value(), add(0, -1_i32));

    assert_eq!(1, add(0_u64, 1_u64));
    assert_eq!(0, add(u64::max_value(), 1_u64));
    assert_eq!(
        u64::max_value() - 1,
        add(u64::max_value(), u64::max_value())
    );

    assert_eq!(1, add(0_u64, U128::from(1)));
    assert_eq!(0, add(u64::max_value(), U128::from(1)));
    assert_eq!(1, add(0_u64, U128::from(u64::max_value()) + 2));
    assert_eq!(u64::max_value(), add(0, U128::max_value()));

    assert_eq!(1, add(0_u64, U256::from(1)));
    assert_eq!(0, add(u64::max_value(), U256::from(1)));
    assert_eq!(1, add(0_u64, U256::from(u64::max_value()) + 2));
    assert_eq!(u64::max_value(), add(0, U256::max_value()));

    assert_eq!(1, add(0_u64, U512::from(1)));
    assert_eq!(0, add(u64::max_value(), U512::from(1)));
    assert_eq!(1, add(0_u64, U512::from(u64::max_value()) + 2));
    assert_eq!(u64::max_value(), add(0, U512::max_value()));

    // Adding to U128
    assert_eq!(U128::from(1), add(U128::zero(), 1_i32));
    assert_eq!(U128::zero(), add(U128::max_value(), 1_i32));
    assert_eq!(U128::zero(), add(U128::from(1), -1_i32));
    assert_eq!(U128::max_value(), add(U128::zero(), -1_i32));

    assert_eq!(U128::from(1), add(U128::zero(), 1_u64));
    assert_eq!(U128::zero(), add(U128::max_value(), 1_u64));

    assert_eq!(U128::from(1), add(U128::zero(), U128::from(1)));
    assert_eq!(U128::zero(), add(U128::max_value(), U128::from(1)));
    assert_eq!(
        U128::max_value() - 1,
        add(U128::max_value(), U128::max_value())
    );

    assert_eq!(U128::from(1), add(U128::zero(), U256::from(1)));
    assert_eq!(U128::zero(), add(U128::max_value(), U256::from(1)));
    assert_eq!(
        U128::from(1),
        add(
            U128::zero(),
            U256::from_dec_str(&U128::max_value().to_string()).unwrap() + 2
        )
    );
    assert_eq!(U128::max_value(), add(U128::zero(), U256::max_value()));

    assert_eq!(U128::from(1), add(U128::zero(), U512::from(1)));
    assert_eq!(U128::zero(), add(U128::max_value(), U512::from(1)));
    assert_eq!(
        U128::from(1),
        add(
            U128::zero(),
            U512::from_dec_str(&U128::max_value().to_string()).unwrap() + 2
        )
    );
    assert_eq!(U128::max_value(), add(U128::zero(), U512::max_value()));

    // Adding to U256
    assert_eq!(U256::from(1), add(U256::zero(), 1_i32));
    assert_eq!(U256::zero(), add(U256::max_value(), 1_i32));
    assert_eq!(U256::zero(), add(U256::from(1), -1_i32));
    assert_eq!(U256::max_value(), add(U256::zero(), -1_i32));

    assert_eq!(U256::from(1), add(U256::zero(), 1_u64));
    assert_eq!(U256::zero(), add(U256::max_value(), 1_u64));

    assert_eq!(U256::from(1), add(U256::zero(), U128::from(1)));
    assert_eq!(U256::zero(), add(U256::max_value(), U128::from(1)));

    assert_eq!(U256::from(1), add(U256::zero(), U256::from(1)));
    assert_eq!(U256::zero(), add(U256::max_value(), U256::from(1)));
    assert_eq!(
        U256::max_value() - 1,
        add(U256::max_value(), U256::max_value())
    );

    assert_eq!(U256::from(1), add(U256::zero(), U512::from(1)));
    assert_eq!(U256::zero(), add(U256::max_value(), U512::from(1)));
    assert_eq!(
        U256::from(1),
        add(
            U256::zero(),
            U512::from_dec_str(&U256::max_value().to_string()).unwrap() + 2
        )
    );
    assert_eq!(U256::max_value(), add(U256::zero(), U512::max_value()));

    // Adding to U512
    assert_eq!(U512::from(1), add(U512::zero(), 1_i32));
    assert_eq!(U512::zero(), add(U512::max_value(), 1_i32));
    assert_eq!(U512::zero(), add(U512::from(1), -1_i32));
    assert_eq!(U512::max_value(), add(U512::zero(), -1_i32));

    assert_eq!(U512::from(1), add(U512::zero(), 1_u64));
    assert_eq!(U512::zero(), add(U512::max_value(), 1_u64));

    assert_eq!(U512::from(1), add(U512::zero(), U128::from(1)));
    assert_eq!(U512::zero(), add(U512::max_value(), U128::from(1)));

    assert_eq!(U512::from(1), add(U512::zero(), U256::from(1)));
    assert_eq!(U512::zero(), add(U512::max_value(), U256::from(1)));

    assert_eq!(U512::from(1), add(U512::zero(), U512::from(1)));
    assert_eq!(U512::zero(), add(U512::max_value(), U512::from(1)));
    assert_eq!(
        U256::max_value() - 1,
        add(U256::max_value(), U256::max_value())
    );
}
