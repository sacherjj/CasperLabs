use crate::bytesrepr::{self, Error, FromBytes, ToBytes};
use alloc::vec::Vec;

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

macro_rules! to_from_bytes_impl {
    ($type:ident, $total_bytes:expr) => {
        impl ToBytes for $type {
            fn to_bytes(&self) -> Vec<u8> {
                let mut buf = [0u8; $total_bytes];
                self.to_little_endian(&mut buf);
                let mut non_zero_bytes: Vec<u8> =
                    buf.iter().rev().skip_while(|b| **b == 0).cloned().collect();
                let num_bytes = non_zero_bytes.len() as u8;
                non_zero_bytes.push(num_bytes);
                non_zero_bytes.reverse();
                non_zero_bytes
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
    };
}

to_from_bytes_impl!(U128, 16);
to_from_bytes_impl!(U256, 32);
to_from_bytes_impl!(U512, 64);
