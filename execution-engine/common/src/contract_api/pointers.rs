use crate::key::Key;
use crate::value::{Account, Contract};
use core::marker::PhantomData;

// URef with type information about what value is in the global state
pub struct UPointer<T>([u8; 32], PhantomData<T>);

impl<T> UPointer<T> {
    pub fn new(id: [u8; 32]) -> UPointer<T> {
        UPointer(id, PhantomData)
    }
}

pub enum ContractPointer {
    Hash([u8; 32]),
    URef(UPointer<Contract>),
}

pub enum AccountPointer {
    Address([u8; 20]),
    URef(UPointer<Account>),
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

impl From<AccountPointer> for Key {
    fn from(a_ptr: AccountPointer) -> Self {
        match a_ptr {
            AccountPointer::Address(a) => Key::Account(a),
            AccountPointer::URef(u_ptr) => u_ptr.into(),
        }
    }
}
