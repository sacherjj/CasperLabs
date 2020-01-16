use core::{any::type_name, convert::TryFrom, fmt, marker::PhantomData};

use hex_fmt::HexFmt;

use casperlabs_types::{AccessRights, ApiError, CLTyped, Key, URef};

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum Error {
    NoAccessRights,
    NotURef,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            Error::NoAccessRights => write!(f, "URef has no access rights"),
            Error::NotURef => write!(f, "Key is not a URef variant"),
        }
    }
}

// TODO: TURef might need to be encoded into more fine grained types rather than hold AccessRights
// as one of the fields in order to be able to statically provide guarantees about how it can
// operate on the keys.

// URef with type information about what value is in the global state
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct TURef<T> {
    addr: [u8; 32],
    access_rights: AccessRights,
    _marker: PhantomData<T>,
}

impl<T: CLTyped> TURef<T> {
    pub fn new(addr: [u8; 32], access_rights: AccessRights) -> Self {
        TURef {
            addr,
            access_rights,
            _marker: PhantomData,
        }
    }

    pub fn from_uref(uref: URef) -> Result<Self, Error> {
        if let Some(access_rights) = uref.access_rights() {
            let addr = uref.addr();
            Ok(TURef {
                addr,
                access_rights,
                _marker: PhantomData,
            })
        } else {
            Err(Error::NoAccessRights)
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

impl<T> core::fmt::Display for TURef<T> {
    fn fmt(&self, f: &mut core::fmt::Formatter) -> core::fmt::Result {
        write!(
            f,
            "TURef({}, {}; {})",
            HexFmt(&self.addr),
            self.access_rights,
            type_name::<T>()
        )
    }
}

impl<T: CLTyped> From<TURef<T>> for URef {
    fn from(input: TURef<T>) -> Self {
        URef::new(input.addr(), input.access_rights())
    }
}

impl<T: CLTyped> TryFrom<Key> for TURef<T> {
    type Error = Error;

    fn try_from(key: Key) -> Result<Self, Self::Error> {
        if let Key::URef(uref) = key {
            TURef::from_uref(uref)
        } else {
            Err(Error::NotURef)
        }
    }
}

impl<T: CLTyped> From<TURef<T>> for Key {
    fn from(turef: TURef<T>) -> Self {
        let uref = URef::new(turef.addr(), turef.access_rights());
        Key::URef(uref)
    }
}

impl From<Error> for ApiError {
    fn from(error: Error) -> Self {
        match error {
            Error::NoAccessRights => ApiError::NoAccessRights,
            Error::NotURef => ApiError::UnexpectedKeyVariant,
        }
    }
}

#[cfg(test)]
mod tests {
    use std::string::{String, ToString};

    use super::*;

    #[test]
    fn turef_as_string() {
        let addr_array = [48u8; 32];
        {
            let turef: TURef<String> = TURef::new(addr_array, AccessRights::ADD);
            assert_eq!(
                turef.to_string(),
                "TURef(3030303030303030303030303030303030303030303030303030303030303030, ADD; alloc::string::String)"
            );
        }

        {
            let turef: TURef<Key> = TURef::new(addr_array, AccessRights::READ_ADD_WRITE);
            assert_eq!(
                turef.to_string(),
                "TURef(3030303030303030303030303030303030303030303030303030303030303030, READ_ADD_WRITE; casperlabs_types::key::Key)"
            );
        }
    }
}
