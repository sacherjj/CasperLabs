use super::TURef;
use crate::key::Key;
use crate::value::Contract;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContractRef {
    Hash([u8; 32]),
    URef(TURef<Contract>),
}

impl ContractRef {
    pub fn into_turef(self) -> Option<TURef<Contract>> {
        match self {
            ContractRef::URef(ret) => Some(ret),
            _ => None,
        }
    }
}

impl From<ContractRef> for Key {
    fn from(c_ptr: ContractRef) -> Self {
        match c_ptr {
            ContractRef::Hash(h) => Key::Hash(h),
            ContractRef::URef(turef) => turef.into(),
        }
    }
}
