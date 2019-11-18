use std::convert::{TryFrom, TryInto};

use contract_ffi::value::Value;

use crate::engine_server::{
    mappings::ParsingError,
    state::{self, IntList, StringList, Unit, Value_oneof_value},
};

impl From<Value> for state::Value {
    fn from(value: Value) -> Self {
        let mut pb_value = state::Value::new();
        match value {
            Value::Int32(x) => pb_value.set_int_value(x),
            Value::UInt64(x) => pb_value.set_long_value(x),
            Value::UInt128(x) => pb_value.set_big_int(x.into()),
            Value::UInt256(x) => pb_value.set_big_int(x.into()),
            Value::UInt512(x) => pb_value.set_big_int(x.into()),
            Value::ByteArray(bytes) => pb_value.set_bytes_value(bytes),
            Value::ListInt32(int_list) => {
                let mut pb_int_list = IntList::new();
                pb_int_list.set_values(int_list);
                pb_value.set_int_list(pb_int_list);
            }
            Value::String(string) => pb_value.set_string_value(string),
            Value::ListString(list_string) => {
                let mut pb_string_list = StringList::new();
                pb_string_list.set_values(list_string.into());
                pb_value.set_string_list(pb_string_list);
            }
            Value::NamedKey(name, key) => {
                pb_value.set_named_key((name, key).into());
            }
            Value::Key(key) => pb_value.set_key(key.into()),
            Value::Account(account) => pb_value.set_account(account.into()),
            Value::Contract(contract) => pb_value.set_contract(contract.into()),
            Value::Unit => pb_value.set_unit(Unit::new()),
        };
        pb_value
    }
}

impl TryFrom<state::Value> for Value {
    type Error = ParsingError;

    fn try_from(pb_value: state::Value) -> Result<Self, Self::Error> {
        let pb_value = pb_value
            .value
            .ok_or_else(|| ParsingError("Unable to parse Protobuf Value".to_string()))?;
        let value = match pb_value {
            Value_oneof_value::int_value(x) => Value::Int32(x),
            Value_oneof_value::long_value(x) => Value::UInt64(x),
            Value_oneof_value::big_int(pb_big_int) => pb_big_int.try_into()?,
            Value_oneof_value::bytes_value(bytes) => Value::ByteArray(bytes),
            Value_oneof_value::int_list(pb_int_list) => Value::ListInt32(pb_int_list.values),
            Value_oneof_value::string_value(string) => Value::String(string),
            Value_oneof_value::string_list(pb_string_list) => {
                Value::ListString(pb_string_list.values.into_vec())
            }
            Value_oneof_value::named_key(pb_named_key) => {
                let (name, key) = pb_named_key.try_into()?;
                Value::NamedKey(name, key)
            }
            Value_oneof_value::key(pb_key) => Value::Key(pb_key.try_into()?),
            Value_oneof_value::account(pb_account) => Value::Account(pb_account.try_into()?),
            Value_oneof_value::contract(pb_contract) => Value::Contract(pb_contract.try_into()?),
            Value_oneof_value::unit(_) => Value::Unit,
        };
        Ok(value)
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use contract_ffi::gens;

    use super::*;
    use crate::engine_server::mappings::test_utils;

    proptest! {
        #[test]
        fn round_trip(value in gens::value_arb()) {
            test_utils::protobuf_round_trip::<Value, state::Value>(value);
        }
    }
}
