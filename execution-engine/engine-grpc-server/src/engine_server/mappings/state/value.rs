use std::convert::{TryFrom, TryInto};

use engine_shared::stored_value::StoredValue;
use types::{bytesrepr::ToBytes, CLType, CLTyped, CLValue, Key, U128, U256, U512};

use crate::engine_server::{
    mappings::ParsingError,
    state::{Value, Value_oneof_value},
};

impl TryFrom<StoredValue> for Value {
    type Error = ParsingError;

    fn try_from(stored_value: StoredValue) -> Result<Self, Self::Error> {
        let mut ret = Value::new();
        let cl_value = match stored_value {
            StoredValue::CLValue(cl_value) => cl_value,
            StoredValue::Account(account) => {
                ret.set_account(account.into());
                return Ok(ret);
            }
            StoredValue::Contract(contract) => {
                ret.set_contract(contract.into());
                return Ok(ret);
            }
        };

        let conversion_error = |cl_value: CLValue| {
            Err(ParsingError(format!(
                "Can't convert {:?} into a protobuf Value",
                StoredValue::CLValue(cl_value)
            )))
        };

        match cl_value.cl_type() {
            CLType::I32 => {
                let x: i32 = cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                ret.set_int_value(x);
            }
            CLType::U64 => {
                let x: u64 = cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                ret.set_long_value(x);
            }
            CLType::U128 => {
                let x: U128 = cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                ret.set_big_int(x.into());
            }
            CLType::U256 => {
                let x: U256 = cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                ret.set_big_int(x.into());
            }
            CLType::U512 => {
                let x: U512 = cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                ret.set_big_int(x.into());
            }
            CLType::Unit => {
                ret.mut_unit();
            }
            CLType::String => {
                let string: String = cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                ret.set_string_value(string);
            }
            CLType::Key => {
                let key: Key = cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                ret.set_key(key.into());
            }
            CLType::List(inner_type) => match **inner_type {
                CLType::U8 => {
                    let bytes: Vec<u8> =
                        cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                    ret.set_bytes_value(bytes);
                }
                CLType::I32 => {
                    let int_list: Vec<i32> =
                        cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                    ret.mut_int_list().set_values(int_list);
                }
                CLType::String => {
                    let string_list: Vec<String> =
                        cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                    ret.mut_string_list().set_values(string_list.into());
                }
                _ => return conversion_error(cl_value),
            },
            CLType::Tuple2(inner_types) => {
                if *inner_types[0] == CLType::String && *inner_types[1] == CLType::Key {
                    let named_key: (String, Key) =
                        cl_value.into_t().map_err(|error| format!("{:?}", error))?;
                    ret.set_named_key(named_key.into());
                } else {
                    return conversion_error(cl_value);
                }
            }
            _ => return conversion_error(cl_value),
        }
        Ok(ret)
    }
}

fn try_stored_value_from<T: CLTyped + ToBytes>(t: T) -> Result<StoredValue, ParsingError> {
    let cl_value = CLValue::from_t(t).map_err(|error| format!("{:?}", error))?;
    Ok(StoredValue::CLValue(cl_value))
}

impl TryFrom<Value> for StoredValue {
    type Error = ParsingError;

    fn try_from(value: Value) -> Result<Self, Self::Error> {
        let value = value
            .value
            .ok_or_else(|| ParsingError("Unable to parse Protobuf Value".to_string()))?;

        let stored_value = match value {
            Value_oneof_value::int_value(x) => try_stored_value_from(x),
            Value_oneof_value::bytes_value(bytes) => try_stored_value_from(bytes),
            Value_oneof_value::int_list(mut pb_int_list) => {
                try_stored_value_from(pb_int_list.take_values())
            }
            Value_oneof_value::string_value(string) => try_stored_value_from(string),
            Value_oneof_value::account(pb_account) => {
                Ok(StoredValue::Account(pb_account.try_into()?))
            }
            Value_oneof_value::contract(pb_contract) => {
                Ok(StoredValue::Contract(pb_contract.try_into()?))
            }
            Value_oneof_value::string_list(mut pb_string_list) => {
                try_stored_value_from(pb_string_list.take_values().into_vec())
            }
            Value_oneof_value::named_key(pb_named_key) => {
                try_stored_value_from(<(String, Key)>::try_from(pb_named_key)?)
            }
            Value_oneof_value::big_int(pb_big_int) => {
                Ok(StoredValue::CLValue(CLValue::try_from(pb_big_int)?))
            }
            Value_oneof_value::key(pb_key) => try_stored_value_from(Key::try_from(pb_key)?),
            Value_oneof_value::unit(_) => try_stored_value_from(()),
            Value_oneof_value::long_value(x) => try_stored_value_from(x),
        }?;

        Ok(stored_value)
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use engine_shared::{account::gens::account_arb, contract::gens::contract_arb};
    use types::gens::cl_value_arb;

    use super::*;

    fn cl_value_is_convertible_to_value(cl_value: &CLValue) -> bool {
        match cl_value.cl_type() {
            CLType::Bool
            | CLType::I64
            | CLType::U8
            | CLType::U32
            | CLType::URef
            | CLType::Option(_)
            | CLType::FixedList(..)
            | CLType::Result { .. }
            | CLType::Map { .. }
            | CLType::Tuple1(_)
            | CLType::Tuple3(_) => false,
            CLType::List(inner_type) => match **inner_type {
                CLType::Bool
                | CLType::I64
                | CLType::U32
                | CLType::U64
                | CLType::U128
                | CLType::U256
                | CLType::U512
                | CLType::Unit
                | CLType::Key
                | CLType::URef
                | CLType::Option(_)
                | CLType::List(_)
                | CLType::FixedList(..)
                | CLType::Result { .. }
                | CLType::Map { .. }
                | CLType::Tuple1(_)
                | CLType::Tuple2(_)
                | CLType::Tuple3(_) => false,
                _ => true,
            },
            CLType::Tuple2(inner_types) => {
                *inner_types[0] == CLType::String && *inner_types[1] == CLType::Key
            }
            _ => true,
        }
    }

    fn do_round_trip(stored_value: StoredValue) {
        let pb_value = Value::try_from(stored_value.clone()).unwrap_or_else(|_| {
            panic!(
                "Expected transforming {:?} into protobuf Value to succeed.",
                stored_value,
            )
        });
        let parsed = StoredValue::try_from(pb_value).unwrap_or_else(|_| {
            panic!(
                "Expected transforming protobuf Value back into {:?} to succeed.",
                stored_value,
            )
        });
        assert_eq!(stored_value, parsed);
    }

    proptest! {
        #[test]
        fn round_trip(account in account_arb(), contract in contract_arb(), cl_value in cl_value_arb()) {
            do_round_trip(StoredValue::Account(account));
            do_round_trip(StoredValue::Contract(contract));

            if cl_value_is_convertible_to_value(&cl_value) {
                do_round_trip(StoredValue::CLValue(cl_value));
            } else {
                assert!(Value::try_from(StoredValue::CLValue(cl_value)).is_err());
            }
        }
    }
}
