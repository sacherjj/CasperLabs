use crate::key::Key;
use crate::uref::AccessRights;
use crate::uref::URef;
use crate::value::Contract;
use core::fmt;
use core::marker::PhantomData;

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub struct NoAccessRightsError;

impl fmt::Display for NoAccessRightsError {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        write!(f, "URef has no access rights")
    }
}

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

    pub fn from_uref(uref: URef) -> Result<Self, NoAccessRightsError> {
        if let Some(access_rights) = uref.access_rights() {
            Ok(UPointer(uref.addr(), access_rights, PhantomData))
        } else {
            Err(NoAccessRightsError)
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContractPointer {
    Hash([u8; 32]),
    URef(UPointer<Contract>),
}

impl<T> From<UPointer<T>> for Key {
    fn from(u_ptr: UPointer<T>) -> Self {
        let uref = URef::new(u_ptr.0, u_ptr.1);
        Key::URef(uref)
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
