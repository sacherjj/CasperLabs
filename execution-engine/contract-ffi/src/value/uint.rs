use alloc::vec::Vec;

use num_traits::{AsPrimitive, Bounded, Num, One, Unsigned, WrappingAdd, WrappingSub, Zero};

use crate::bytesrepr::{self, Error, FromBytes, ToBytes};

#[allow(
    clippy::assign_op_pattern,
    clippy::ptr_offset_with_cast,
    clippy::range_plus_one,
    clippy::transmute_ptr_to_ptr
)]
mod macro_code {
    use uint::construct_uint;

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

        // Instead of implementing arbitrary methods we can use existing traits from num_trait
        // crate.
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

        impl AsPrimitive<$type> for i32 {
            fn as_(self) -> $type {
                if self >= 0 {
                    $type::from(self as u32)
                } else {
                    let abs = 0u32.wrapping_sub(self as u32);
                    $type::zero().wrapping_sub(&$type::from(abs))
                }
            }
        }

        impl AsPrimitive<$type> for i64 {
            fn as_(self) -> $type {
                if self >= 0 {
                    $type::from(self as u64)
                } else {
                    let abs = 0u64.wrapping_sub(self as u64);
                    $type::zero().wrapping_sub(&$type::from(abs))
                }
            }
        }

        impl AsPrimitive<$type> for u8 {
            fn as_(self) -> $type {
                $type::from(self)
            }
        }

        impl AsPrimitive<$type> for u32 {
            fn as_(self) -> $type {
                $type::from(self)
            }
        }

        impl AsPrimitive<$type> for u64 {
            fn as_(self) -> $type {
                $type::from(self)
            }
        }

        impl AsPrimitive<i32> for $type {
            fn as_(self) -> i32 {
                self.0[0] as i32
            }
        }

        impl AsPrimitive<i64> for $type {
            fn as_(self) -> i64 {
                self.0[0] as i64
            }
        }

        impl AsPrimitive<u8> for $type {
            fn as_(self) -> u8 {
                self.0[0] as u8
            }
        }

        impl AsPrimitive<u32> for $type {
            fn as_(self) -> u32 {
                self.0[0] as u32
            }
        }

        impl AsPrimitive<u64> for $type {
            fn as_(self) -> u64 {
                self.0[0]
            }
        }
    };
}

ser_and_num_impls!(U128, 16);
ser_and_num_impls!(U256, 32);
ser_and_num_impls!(U512, 64);

impl AsPrimitive<U128> for U128 {
    fn as_(self) -> U128 {
        self
    }
}

impl AsPrimitive<U256> for U128 {
    fn as_(self) -> U256 {
        let mut result = U256::zero();
        result.0[..2].clone_from_slice(&self.0[..2]);
        result
    }
}

impl AsPrimitive<U512> for U128 {
    fn as_(self) -> U512 {
        let mut result = U512::zero();
        result.0[..2].clone_from_slice(&self.0[..2]);
        result
    }
}

impl AsPrimitive<U128> for U256 {
    fn as_(self) -> U128 {
        let mut result = U128::zero();
        result.0[..2].clone_from_slice(&self.0[..2]);
        result
    }
}

impl AsPrimitive<U256> for U256 {
    fn as_(self) -> U256 {
        self
    }
}

impl AsPrimitive<U512> for U256 {
    fn as_(self) -> U512 {
        let mut result = U512::zero();
        result.0[..4].clone_from_slice(&self.0[..4]);
        result
    }
}

impl AsPrimitive<U128> for U512 {
    fn as_(self) -> U128 {
        let mut result = U128::zero();
        result.0[..2].clone_from_slice(&self.0[..2]);
        result
    }
}

impl AsPrimitive<U256> for U512 {
    fn as_(self) -> U256 {
        let mut result = U256::zero();
        result.0[..4].clone_from_slice(&self.0[..4]);
        result
    }
}

impl AsPrimitive<U512> for U512 {
    fn as_(self) -> U512 {
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
