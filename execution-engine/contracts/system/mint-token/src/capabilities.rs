use core::{convert::TryFrom, marker::PhantomData};

use contract::contract_api::{storage, TURef};
use types::{
    bytesrepr::{FromBytes, ToBytes},
    AccessRights, CLTyped, Key,
};

/// Trait representing the ability to read a value. Use case: a key
/// for the blockdag global state (`TURef`) is obviously Readable,
/// however if we abstract over it then we can write unit tests for
/// smart contracts much more easily.
pub trait Readable<T> {
    fn read(self) -> T;
}

/// Trait representing the ability to write a value. See `Readable`
/// for use case.
pub trait Writable<T> {
    fn write(self, value: T);
}

/// Trait representing the ability to add a value `t` to some stored
/// value of the same type. See `Readable` for use case.
pub trait Addable<T> {
    fn add(self, value: T);
}

/// Add-only URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct RefWithAddRights<T>([u8; 32], PhantomData<T>);

impl<T> RefWithAddRights<T> {
    const fn access_rights() -> AccessRights {
        AccessRights::ADD
    }
}

impl<T> TryFrom<Key> for RefWithAddRights<T> {
    type Error = ();

    fn try_from(key: Key) -> Result<Self, Self::Error> {
        match key {
            Key::URef(uref) if uref.access_rights() >= Some(Self::access_rights()) => {
                Ok(RefWithAddRights(uref.addr(), PhantomData))
            }
            _ => Err(()),
        }
    }
}

impl<T: CLTyped + ToBytes> Addable<T> for RefWithAddRights<T> {
    fn add(self, value: T) {
        let turef = TURef::<T>::new(self.0, Self::access_rights());
        storage::add(turef, value);
    }
}

/// Read, Add, Write URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct RefWithReadAddWriteRights<T>([u8; 32], PhantomData<T>);

impl<T> RefWithReadAddWriteRights<T> {
    const fn access_rights() -> AccessRights {
        AccessRights::READ_ADD_WRITE
    }
}

impl<T: CLTyped> Into<TURef<T>> for RefWithReadAddWriteRights<T> {
    fn into(self) -> TURef<T> {
        TURef::new(self.0, Self::access_rights())
    }
}

impl<T> TryFrom<Key> for RefWithReadAddWriteRights<T> {
    type Error = ();

    fn try_from(key: Key) -> Result<Self, Self::Error> {
        match key {
            Key::URef(uref) if uref.access_rights() >= Some(Self::access_rights()) => {
                Ok(RefWithReadAddWriteRights(uref.addr(), PhantomData))
            }
            _ => Err(()),
        }
    }
}

impl<T: CLTyped + FromBytes> Readable<T> for RefWithReadAddWriteRights<T> {
    fn read(self) -> T {
        let turef = self.into();
        storage::read(turef)
            .expect("value should deserialize")
            .expect("should find value")
    }
}

impl<T: CLTyped + ToBytes> Writable<T> for RefWithReadAddWriteRights<T> {
    fn write(self, value: T) {
        let turef = self.into();
        storage::write(turef, value);
    }
}

impl<T: CLTyped + ToBytes + FromBytes> Addable<T> for RefWithReadAddWriteRights<T> {
    fn add(self, value: T) {
        let turef = self.into();
        storage::add(turef, value);
    }
}
