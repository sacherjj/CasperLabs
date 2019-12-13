use contract_ffi::value::CLType;
use engine_shared::stored_value::StoredValue;

use crate::engine_server::state::Value;

impl From<StoredValue> for Value {
    fn from(stored_value: StoredValue) -> Self {
        let mut ret = Value::new();
        let (cl_type, _cl_value) = match stored_value {
            StoredValue::CLValue(cl_value) => cl_value.destructure(),
            StoredValue::Account(account) => {
                ret.set_account(account.into());
                return ret;
            }
            StoredValue::Contract(contract) => {
                ret.set_contract(contract.into());
                return ret;
            }
        };
        match cl_type {
            CLType::Bool => {}
            CLType::I32 => {}
            CLType::I64 => {}
            CLType::U8 => {}
            CLType::U32 => {}
            CLType::U64 => {}
            CLType::U128 => {}
            CLType::U256 => {}
            CLType::U512 => {}
            CLType::Unit => {}
            CLType::String => {}
            CLType::Key => {}
            CLType::URef => {}
            CLType::Option(_) => {}
            CLType::List(_) => {}
            CLType::FixedList(_, _) => {}
            CLType::Result { .. } => {}
            CLType::Map { .. } => {}
            CLType::Tuple1(_) => {}
            CLType::Tuple2(_) => {}
            CLType::Tuple3(_) => {}
            CLType::Tuple4(_) => {}
            CLType::Tuple5(_) => {}
            CLType::Tuple6(_) => {}
            CLType::Tuple7(_) => {}
            CLType::Tuple8(_) => {}
            CLType::Tuple9(_) => {}
            CLType::Tuple10(_) => {}
        }
        ret
    }
}

impl From<Value> for StoredValue {
    fn from(_value: Value) -> Self {
        unimplemented!()
    }
}
