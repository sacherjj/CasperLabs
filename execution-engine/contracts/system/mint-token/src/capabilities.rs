use core::convert::TryFrom;
use core::marker::PhantomData;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::TURef;
use contract_ffi::key::Key;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::Value;

/// Trait representing the ability to read a value. Use case: a key
/// for the blockdag global state (`TURef`) is obviously Readable,
/// however if we abstract over it then we can write unit tests for
/// smart contracts much more easily.
pub trait Readable<T> {
    fn read(&self) -> T;
}

macro_rules! readable_impl {
    ($type:ty) => {
        impl<T> Readable<T> for $type
        where
            T: TryFrom<Value> + Clone,
        {
            fn read(&self) -> T {
                let turef: TURef<T> = self.clone().into();
                contract_api::read(turef)
            }
        }
    };
}

/// Trait representing the ability to write a value. See `Readable`
/// for use case.
pub trait Writable<T> {
    fn write(&self, t: T);
}

macro_rules! writeable_impl {
    ($type:ty) => {
        impl<T> Writable<T> for $type
        where
            Value: From<T>,
            T: Clone,
        {
            fn write(&self, t: T) {
                let turef: TURef<T> = self.clone().into();
                contract_api::write(turef, t);
            }
        }
    };
}

/// Trait representing the ability to add a value `t` to some stored
/// value of the same type. See `Readable` for use case.
pub trait Addable<T> {
    fn add(&self, t: T);
}

macro_rules! addable_impl {
    ($type:ty) => {
        impl<T> Addable<T> for $type
        where
            Value: From<T>,
            T: Clone,
        {
            fn add(&self, t: T) {
                let turef: TURef<T> = self.clone().into();
                contract_api::add(turef, t);
            }
        }
    };
}

/// Macro for deriving conversion traits to/from TURef
macro_rules! into_try_from_turef_impl {
    ($type:ident, $min_access:expr) => {
        impl<T> TryFrom<TURef<T>> for $type<T> {
            type Error = ();

            fn try_from(u: TURef<T>) -> Result<Self, Self::Error> {
                if u.access_rights() & $min_access == $min_access {
                    Ok($type(u.addr(), PhantomData))
                } else {
                    Err(())
                }
            }
        }

        // Can't implement From<$type<T>> for TURef<T> because of the
        // type parameter on TURef and TURef is not part of this crate.
        impl<T> Into<TURef<T>> for $type<T> {
            fn into(self) -> TURef<T> {
                let access = $min_access;
                TURef::new(self.0, access)
            }
        }
    };
}

/// Macro for deriving conversion traits to/from Key
macro_rules! from_try_from_key_impl {
    ($type:ident, $min_access:expr) => {
        impl<T> TryFrom<Key> for $type<T> {
            type Error = ();

            fn try_from(k: Key) -> Result<Self, Self::Error> {
                match k {
                    Key::URef(uref) if uref.access_rights() >= Some($min_access) => {
                        Ok($type(uref.addr(), PhantomData))
                    }
                    _ => Err(()),
                }
            }
        }

        impl<T> From<$type<T>> for Key {
            fn from(x: $type<T>) -> Self {
                let access = $min_access;
                Key::URef(URef::new(x.0, access))
            }
        }
    };
}

/// Add-only URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct ARef<T>(pub [u8; 32], PhantomData<T>);

into_try_from_turef_impl!(ARef, AccessRights::ADD);
from_try_from_key_impl!(ARef, AccessRights::ADD);
addable_impl!(ARef<T>);

/// Read+Write URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct RAWRef<T>(pub [u8; 32], PhantomData<T>);

into_try_from_turef_impl!(RAWRef, AccessRights::READ_ADD_WRITE);
from_try_from_key_impl!(RAWRef, AccessRights::READ_ADD_WRITE);
readable_impl!(RAWRef<T>);
addable_impl!(RAWRef<T>);
writeable_impl!(RAWRef<T>);
