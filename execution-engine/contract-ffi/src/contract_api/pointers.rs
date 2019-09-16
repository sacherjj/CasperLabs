use crate::key::Key;
use crate::uref::AccessRights;
use crate::uref::URef;
use crate::value::Contract;
use core::fmt;
use core::marker::PhantomData;

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum AccessRightsError {
    NoAccessRights,
}

impl fmt::Display for AccessRightsError {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            AccessRightsError::NoAccessRights => write!(f, "URef has no access rights"),
        }
    }
}

// TODO: TURef might needs to be encoded into more fine grained types
// rather than hold AccessRights as one of the fields in order to be able
// to statically provide guarantees about how it can operate on the keys.

// URef with type information about what value is in the global state
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct TURef<T> {
    addr: [u8; 32],
    access_rights: AccessRights,
    _marker: PhantomData<T>,
}

impl<T> TURef<T> {
    pub fn new(addr: [u8; 32], access_rights: AccessRights) -> TURef<T> {
        TURef {
            addr,
            access_rights,
            _marker: PhantomData,
        }
    }

    pub fn from_uref(uref: URef) -> Result<Self, AccessRightsError> {
        if let Some(access_rights) = uref.access_rights() {
            Ok(TURef::new(uref.addr(), access_rights))
        } else {
            Err(AccessRightsError::NoAccessRights)
        }
    }

    pub fn addr(&self) -> [u8; 32] {
        self.addr
    }

    pub fn access_rights(&self) -> AccessRights {
        self.access_rights
    }

    pub fn set_access_rights(&mut self, access_rights: AccessRights) {
        self.access_rights = access_rights;
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContractPointer {
    Hash([u8; 32]),
    URef(TURef<Contract>),
}

impl<T> From<TURef<T>> for Key {
    fn from(turef: TURef<T>) -> Self {
        let uref = URef::new(turef.addr(), turef.access_rights());
        Key::URef(uref)
    }
}

impl From<ContractPointer> for Key {
    fn from(c_ptr: ContractPointer) -> Self {
        match c_ptr {
            ContractPointer::Hash(h) => Key::Hash(h),
            ContractPointer::URef(turef) => turef.into(),
        }
    }
}
