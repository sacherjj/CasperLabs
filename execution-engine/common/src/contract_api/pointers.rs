use crate::key::Key;
use crate::value::Contract;
use core::marker::PhantomData;

// URef with type information about what value is in the global state
#[derive(Clone)]
pub struct UPointer<T>([u8; 32], PhantomData<T>);

impl<T> UPointer<T> {
    pub fn new(id: [u8; 32]) -> UPointer<T> {
        UPointer(id, PhantomData)
    }
}

#[derive(Clone)]
pub enum ContractPointer {
    Hash([u8; 32]),
    URef(UPointer<Contract>),
}

impl<T> From<UPointer<T>> for Key {
    fn from(u_ptr: UPointer<T>) -> Self {
        Key::URef(u_ptr.0)
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
