use crate::bytesrepr::{self, Error, FromBytes, ToBytes};
use alloc::vec::Vec;
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

/// Trait to allow writing generic functions which use the
/// `checked_add` function. Can't use num::CheckedAdd because it is
/// defined using references (e.g. &self) which does not work with the
/// definitions in uint crate (where U128, etc. come from)
pub trait CheckedAdd: core::ops::Add<Self, Output = Self> + Sized {
    fn checked_add(self, v: Self) -> Option<Self>;
}

/// Trait to allow writing generic functions which use the
/// `checked_sub` function. Can't use num::CheckedSub because it is
/// defined using references (e.g. &self) which does not work with the
/// definitions in uint crate (where U128, etc. come from)
pub trait CheckedSub: core::ops::Sub<Self, Output = Self> + Sized {
    fn checked_sub(self, v: Self) -> Option<Self>;
}

macro_rules! ser_and_num_impls {
    ($type:ident, $total_bytes:expr) => {
        impl ToBytes for $type {
            type Error = Error;
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

        // Trait implementations for uinifying U* as numeric types
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

        impl CheckedAdd for $type {
            fn checked_add(self, v: Self) -> Option<Self> {
                $type::checked_add(self, v)
            }
        }

        impl CheckedSub for $type {
            fn checked_sub(self, v: Self) -> Option<Self> {
                $type::checked_sub(self, v)
            }
        }

        // Additional numeric trait, which also holds for these types
        impl Bounded for $type {
            fn min_value() -> Self {
                $type::zero()
            }

            fn max_value() -> Self {
                $type::MAX
            }
        }
    };
}

ser_and_num_impls!(U128, 16);
ser_and_num_impls!(U256, 32);
ser_and_num_impls!(U512, 64);
