use cl_std::contract_api;
use cl_std::contract_api::pointers::UPointer;
use cl_std::key::{AccessRights, Key};
use cl_std::value::Value;

use alloc::rc::Rc;
use core::cell::Cell;
use core::convert::TryFrom;
use core::marker::PhantomData;
use core::ops::Add;

/// Trait representing the ability to read a value. Use case: a key
/// for the blockdag global state (`UPointer`) is obviously Readable,
/// however if we abstract over it then we can write unit tests for
/// smart contracts much more easily.
pub trait Readable<T> {
    fn read(&self) -> T;
}

/// Trait representing the ability to write a value. See `Readable`
/// for use case.
pub trait Writable<T> {
    fn write(&self, t: T);
}

/// Trait representing the ability to add a value `t` to some stored
/// value of the same type. See `Readable` for use case.
pub trait Addable<T> {
    fn add(&self, t: T);
}

/// Macro for deriving conversion traits to/from UPointer
macro_rules! from_try_from_impl {
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

        // Can't implement From<$type<T>> for UPointer<T> because of the
        // type parameter on UPointer and UPointer is not part of this crate.
        impl<T> Into<UPointer<T>> for $type<T> {
            fn into(self) -> UPointer<T> {
                let access = $min_access;
                UPointer::new(self.0, access)
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

/// Read-only URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct RRef<T>(pub [u8; 32], PhantomData<T>);

/// Write-only URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct WRef<T>(pub [u8; 32], PhantomData<T>);

/// Add-only URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct ARef<T>(pub [u8; 32], PhantomData<T>);

/// Read+Add URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct RARef<T>(pub [u8; 32], PhantomData<T>);

/// Read+Write URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct RWRef<T>(pub [u8; 32], PhantomData<T>);

/// Add+Write URef
#[derive(Debug, Clone, PartialEq, Eq, Copy, Hash)]
pub struct AWRef<T>(pub [u8; 32], PhantomData<T>);

from_try_from_impl!(RRef, AccessRights::READ);
from_try_from_impl!(WRef, AccessRights::WRITE);
from_try_from_impl!(ARef, AccessRights::ADD);
from_try_from_impl!(RARef, AccessRights::READ_ADD);
from_try_from_impl!(RWRef, AccessRights::READ_WRITE);
from_try_from_impl!(AWRef, AccessRights::ADD_WRITE);

// ----------------- Implementations for URefs -----------------
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

readable_impl!(RRef<T>);
readable_impl!(RARef<T>);
readable_impl!(RWRef<T>);

writeable_impl!(WRef<T>);
writeable_impl!(RWRef<T>);
writeable_impl!(AWRef<T>);

addable_impl!(ARef<T>);
addable_impl!(RARef<T>);
addable_impl!(AWRef<T>);
addable_impl!(RWRef<T>); // Read+Write => Add

// ----------------------------------------------------------------

// ------ Implementations for Cell (useful in tests) --------------
impl<T: Copy> Readable<T> for Cell<T> {
    fn read(&self) -> T {
        self.get()
    }
}

impl<T> Writable<T> for Cell<T> {
    fn write(&self, t: T) {
        self.set(t);
    }
}

impl<T: Add<Output = T> + Copy> Addable<T> for Cell<T> {
    fn add(&self, t: T) {
        self.update(|x| x + t);
    }
}
// ----------------------------------------------------------------

// ------ Implementations for RC (useful in tests) ----------------
impl<T, R: Readable<T>> Readable<T> for Rc<R> {
    fn read(&self) -> T {
        R::read(self)
    }
}

impl<T, W: Writable<T>> Writable<T> for Rc<W> {
    fn write(&self, t: T) {
        W::write(self, t)
    }
}

impl<T, A: Addable<T>> Addable<T> for Rc<A> {
    fn add(&self, t: T) {
        A::add(self, t)
    }
}
// ----------------------------------------------------------------
