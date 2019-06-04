use crate::key::AccessRights;
use crate::key::Key;
use crate::value::Contract;
use core::marker::PhantomData;

// TODO: UPointer might needs to be encoded into more fine grained types
// rather than hold AccessRights as one of the fields in order to be able
// to statically provide guarantees about how it can operate on the keys.

// URef with type information about what value is in the global state
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct UPointer<T>(pub [u8; 32], pub AccessRights, pub PhantomData<T>);

impl<T> UPointer<T> {
    pub fn new(id: [u8; 32], rights: AccessRights) -> UPointer<T> {
        UPointer(id, rights, PhantomData)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContractPointer {
    Hash([u8; 32]),
    URef(UPointer<Contract>),
}

impl<T> From<UPointer<T>> for Key {
    fn from(u_ptr: UPointer<T>) -> Self {
        Key::URef(u_ptr.0, Some(u_ptr.1))
    }
}

impl From<ContractPointer> for Key {
    fn from(c_ptr: ContractPointer) -> Self {
        match c_ptr {
            ContractPointer::Hash(h) => Key::Hash(h),
            ContractPointer::URef(u_ptr) => u_ptr.into(),
        }
    }
}
