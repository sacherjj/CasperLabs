use core::convert::TryFrom;
use core::marker::PhantomData;

use cl_std::contract_api;
use cl_std::contract_api::pointers::UPointer;
use cl_std::key::{AccessRights, Key};
use cl_std::value::Value;

/// Trait representing the ability to read a value. Use case: a key
/// for the blockdag global state (`UPointer`) is obviously Readable,
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
                let ptr: UPointer<T> = self.clone().into();
                contract_api::read(ptr)
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
                let ptr: UPointer<T> = self.clone().into();
                contract_api::write(ptr, t);
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
                let ptr: UPointer<T> = self.clone().into();
                contract_api::add(ptr, t);
            }
        }
    };
}

/// Macro for deriving conversion traits to/from UPointer
macro_rules! from_try_from_upointer_impl {
    ($type:ident, $min_access:expr) => {
        impl<T> TryFrom<UPointer<T>> for $type<T> {
            type Error = ();

            fn try_from(u: UPointer<T>) -> Result<Self, Self::Error> {
                let UPointer(id, access, phantom) = u;
                if access & $min_access == $min_access {
                    Ok($type(id, phantom))
                } else {
                    Err(())
                }
            }
        }

        // Can't implement From<$type<T>> for UPointer<T> because of the
        // type parameter on UPointer and UPointer is not part of this crate.
        impl<T> Into<UPointer<T>> for $type<T> {
            fn into(self) -> UPointer<T> {
                let access = $min_access;
                UPointer::new(self.0, access)
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
                    Key::URef(id, access) => {
                        if access >= $min_access {
                            Ok($type(id, PhantomData))
                        } else {
                            Err(())
                        }
                    }

                    _ => Err(()),
                }
            }
        }

        impl<T> From<$type<T>> for Key {
            fn from(x: $type<T>) -> Self {
                let access = $min_access;
                Key::URef(x.0, access)
            }
        }
    };
}

/// Add-only URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct ARef<T>(pub [u8; 32], PhantomData<T>);

from_try_from_upointer_impl!(ARef, AccessRights::ADD);
from_try_from_key_impl!(ARef, AccessRights::ADD);
addable_impl!(ARef<T>);

/// Read+Write URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct RAWRef<T>(pub [u8; 32], PhantomData<T>);

from_try_from_upointer_impl!(RAWRef, AccessRights::READ_ADD_WRITE);
from_try_from_key_impl!(RAWRef, AccessRights::READ_ADD_WRITE);
readable_impl!(RAWRef<T>);
addable_impl!(RAWRef<T>);
writeable_impl!(RAWRef<T>);
