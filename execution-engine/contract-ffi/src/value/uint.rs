use crate::bytesrepr::{self, Error, FromBytes, ToBytes};
use alloc::vec::Vec;
use num::traits::{WrappingAdd, WrappingSub};
use num::{Bounded, Num, One, Unsigned, Zero};

// Clippy generates a ton of warnings/errors for the code the macro generates.
#[allow(clippy::all)]
mod macro_code {
    construct_uint! {
        pub struct U512(8);
    }
    construct_uint! {
        pub struct U256(4);
    }
    construct_uint! {
        pub struct U128(2);
    }
}

pub use self::macro_code::{U128, U256, U512};

/// Error type for parsing U128, U256, U512 from a string.
/// `FromDecStr` is the parsing error from the `uint` crate, which
/// only supports base-10 parsing. `InvalidRadix` is raised when
/// parsing is attempted on any string representing the number in some
/// base other than 10 presently, however a general radix may be
/// supported in the future.
#[derive(Debug)]
pub enum UIntParseError {
    FromDecStr(uint::FromDecStrErr),
    InvalidRadix,
}

macro_rules! ser_and_num_impls {
    ($type:ident, $total_bytes:expr) => {
        impl ToBytes for $type {
            fn to_bytes(&self) -> Result<Vec<u8>, Error> {
                let mut buf = [0u8; $total_bytes];
                self.to_little_endian(&mut buf);
                let mut non_zero_bytes: Vec<u8> =
                    buf.iter().rev().skip_while(|b| **b == 0).cloned().collect();
                let num_bytes = non_zero_bytes.len() as u8;
                non_zero_bytes.push(num_bytes);
                non_zero_bytes.reverse();
                Ok(non_zero_bytes)
            }
        }

        impl FromBytes for $type {
            fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
                let (num_bytes, rem): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;

                if num_bytes > $total_bytes {
                    Err(Error::FormattingError)
                } else {
                    let (value, rem) = bytesrepr::safe_split_at(rem, num_bytes as usize)?;
                    let result = $type::from_little_endian(value);
                    Ok((result, rem))
                }
            }
        }

        // Trait implementations for unifying U* as numeric types
        impl Zero for $type {
            fn zero() -> Self {
                $type::zero()
            }

            fn is_zero(&self) -> bool {
                self.is_zero()
            }
        }

        impl One for $type {
            fn one() -> Self {
                $type::one()
            }
        }

        // Requires Zero and One to be implemented
        impl Num for $type {
            type FromStrRadixErr = UIntParseError;
            fn from_str_radix(str: &str, radix: u32) -> Result<Self, Self::FromStrRadixErr> {
                if radix == 10 {
                    $type::from_dec_str(str).map_err(UIntParseError::FromDecStr)
                } else {
                    // TODO: other radix parsing
                    Err(UIntParseError::InvalidRadix)
                }
            }
        }

        // Requires Num to be implemented
        impl Unsigned for $type {}

        // Additional numeric trait, which also holds for these types
        impl Bounded for $type {
            fn min_value() -> Self {
                $type::zero()
            }

            fn max_value() -> Self {
                $type::MAX
            }
        }

        // Instead of implementing arbitrary methods we can use existing traits from num crate.
        impl WrappingAdd for $type {
            fn wrapping_add(&self, other: &$type) -> $type {
                self.overflowing_add(*other).0
            }
        }

        impl WrappingSub for $type {
            fn wrapping_sub(&self, other: &$type) -> $type {
                self.overflowing_sub(*other).0
            }
        }
    };
}

ser_and_num_impls!(U128, 16);
ser_and_num_impls!(U256, 32);
ser_and_num_impls!(U512, 64);

#[test]
fn wrapping_test_u512() {
    let max = U512::max_value();
    let value = max.wrapping_add(&1.into());
    assert_eq!(value, 0.into());

    let min = U512::min_value();
    let value = min.wrapping_sub(&1.into());
    assert_eq!(value, U512::max_value());
}

#[test]
fn wrapping_test_u256() {
    let max = U256::max_value();
    let value = max.wrapping_add(&1.into());
    assert_eq!(value, 0.into());

    let min = U256::min_value();
    let value = min.wrapping_sub(&1.into());
    assert_eq!(value, U256::max_value());
}

#[test]
fn wrapping_test_u128() {
    let max = U128::max_value();
    let value = max.wrapping_add(&1.into());
    assert_eq!(value, 0.into());

    let min = U128::min_value();
    let value = min.wrapping_sub(&1.into());
    assert_eq!(value, U128::max_value());
}
