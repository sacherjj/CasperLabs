use crate::{Key, URef};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContractRef {
    Hash([u8; 32]),
    URef(URef),
}

impl ContractRef {
    pub fn into_uref(self) -> Option<URef> {
        match self {
            ContractRef::URef(ret) => Some(ret),
            _ => None,
        }
    }
}

impl From<ContractRef> for Key {
    fn from(contract_ref: ContractRef) -> Self {
        match contract_ref {
            ContractRef::Hash(h) => Key::Hash(h),
            ContractRef::URef(uref) => uref.into(),
        }
    }
}
