//! Mappings for uint types (e.g. common::value::U512, ipc::RustBigInt)

use super::{parse_error, state, ParsingError};
use common::value::U512;
use std::convert::TryFrom;

/// Map a result into the expected error for this module, while also
/// converting the type into a Value. Use case: parsing U128, U256,
/// U512 from a string.
fn result_to_value<T, E>(r: Result<T, E>) -> Result<common::value::Value, ParsingError>
where
    common::value::Value: From<T>,
    E: std::fmt::Debug,
{
    r.map(common::value::Value::from)
        .map_err(|e| ParsingError(format!("{:?}", e)))
}

impl TryFrom<&state::BigInt> for common::value::Value {
    type Error = ParsingError;

    fn try_from(b: &state::BigInt) -> Result<common::value::Value, ParsingError> {
        let n = b.get_value();
        match b.get_bit_width() {
            128 => result_to_value(common::value::U128::from_dec_str(n)),
            256 => result_to_value(common::value::U256::from_dec_str(n)),
            512 => result_to_value(common::value::U512::from_dec_str(n)),
            other => parse_error(format!("BigInt bit width of {} is invalid", other)),
        }
    }
}

impl TryFrom<&state::BigInt> for U512 {
    type Error = ParsingError;

    fn try_from(b: &state::BigInt) -> Result<U512, ParsingError> {
        let n = b.get_value();
        match b.get_bit_width() {
            512 => {
                common::value::U512::from_dec_str(n).map_err(|e| ParsingError(format!("{:?}", e)))
            }
            other => parse_error(format!("BigInt bit width of {} is invalid", other)),
        }
    }
}

macro_rules! from_uint_for_rust_big_int {
    ($type:ty, $bit_width:expr) => {
        impl From<$type> for super::state::BigInt {
            fn from(u: $type) -> Self {
                let mut b = super::state::BigInt::new();
                b.set_value(format!("{}", u));
                b.set_bit_width($bit_width);
                b
            }
        }
    };
}

from_uint_for_rust_big_int!(common::value::U128, 128);
from_uint_for_rust_big_int!(common::value::U256, 256);
from_uint_for_rust_big_int!(common::value::U512, 512);
