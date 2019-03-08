//! Some macros.

/// A macro for implementing common traits for newtypes based on arrays.
///
/// Borrowed from [rust-secp256k1](https://github.com/rust-bitcoin/rust-secp256k1/blob/b192157ab418214f779c5a3a94f4c1b9df4c232a/src/macros.rs).
#[macro_export]
macro_rules! impl_array_newtype {
    ($thing:ident, $ty:ty, $len:expr) => {
        impl Copy for $thing {}

        impl $thing {
            #[inline]
            /// Converts the object to a raw pointer for FFI interfacing
            pub fn as_ptr(&self) -> *const $ty {
                let &$thing(ref dat) = self;
                dat.as_ptr()
            }

            #[inline]
            /// Converts the object to a mutable raw pointer for FFI interfacing
            pub fn as_mut_ptr(&mut self) -> *mut $ty {
                let &mut $thing(ref mut dat) = self;
                dat.as_mut_ptr()
            }

            #[inline]
            /// Returns the length of the object as an array
            pub fn len(&self) -> usize {
                $len
            }

            #[inline]
            /// Returns whether the object as an array is empty
            pub fn is_empty(&self) -> bool {
                $len == 0
            }
        }

        impl PartialEq for $thing {
            #[inline]
            fn eq(&self, other: &$thing) -> bool {
                &self[..] == &other[..]
            }
        }

        impl Eq for $thing {}

        impl PartialOrd for $thing {
            #[inline]
            fn partial_cmp(&self, other: &$thing) -> Option<::std::cmp::Ordering> {
                self[..].partial_cmp(&other[..])
            }
        }

        impl Ord for $thing {
            #[inline]
            fn cmp(&self, other: &$thing) -> ::std::cmp::Ordering {
                self[..].cmp(&other[..])
            }
        }

        impl Clone for $thing {
            #[inline]
            fn clone(&self) -> $thing {
                unsafe {
                    use std::intrinsics::copy_nonoverlapping;
                    use std::mem;
                    let mut ret: $thing = mem::uninitialized();
                    copy_nonoverlapping(self.as_ptr(), ret.as_mut_ptr(), $len);
                    ret
                }
            }
        }

        impl ::std::ops::Index<usize> for $thing {
            type Output = $ty;

            #[inline]
            fn index(&self, index: usize) -> &$ty {
                let &$thing(ref dat) = self;
                &dat[index]
            }
        }

        impl ::std::ops::Index<::std::ops::Range<usize>> for $thing {
            type Output = [$ty];

            #[inline]
            fn index(&self, index: ::std::ops::Range<usize>) -> &[$ty] {
                let &$thing(ref dat) = self;
                &dat[index]
            }
        }

        impl ::std::ops::Index<::std::ops::RangeTo<usize>> for $thing {
            type Output = [$ty];

            #[inline]
            fn index(&self, index: ::std::ops::RangeTo<usize>) -> &[$ty] {
                let &$thing(ref dat) = self;
                &dat[index]
            }
        }

        impl ::std::ops::Index<::std::ops::RangeFrom<usize>> for $thing {
            type Output = [$ty];

            #[inline]
            fn index(&self, index: ::std::ops::RangeFrom<usize>) -> &[$ty] {
                let &$thing(ref dat) = self;
                &dat[index]
            }
        }

        impl ::std::ops::Index<::std::ops::RangeFull> for $thing {
            type Output = [$ty];

            #[inline]
            fn index(&self, _: ::std::ops::RangeFull) -> &[$ty] {
                let &$thing(ref dat) = self;
                &dat[..]
            }
        }

        impl ::std::hash::Hash for $thing {
            #[inline]
            fn hash<H: ::std::hash::Hasher>(&self, state: &mut H) {
                self.0.hash(state);
            }
        }
    };
}

/// A macro for implementing a prettier `Debug` for newtypes based on arrays.
///
/// Borrowed from [rust-secp256k1](https://github.com/rust-bitcoin/rust-secp256k1/blob/b192157ab418214f779c5a3a94f4c1b9df4c232a/src/macros.rs).
#[macro_export]
macro_rules! impl_pretty_debug {
    ($thing:ident) => {
        impl ::std::fmt::Debug for $thing {
            fn fmt(&self, f: &mut ::std::fmt::Formatter) -> ::std::fmt::Result {
                write!(f, "{}(", stringify!($thing))?;
                for i in self[..].iter().cloned() {
                    write!(f, "{:02x}", i)?;
                }
                write!(f, ")")
            }
        }
    };
}

/// A macro for implementing `Debug` for newtypes based on arrays.
///
/// Borrowed from [rust-secp256k1](https://github.com/rust-bitcoin/rust-secp256k1/blob/b192157ab418214f779c5a3a94f4c1b9df4c232a/src/macros.rs).
#[macro_export]
macro_rules! impl_raw_debug {
    ($thing:ident) => {
        impl ::std::fmt::Debug for $thing {
            fn fmt(&self, f: &mut ::std::fmt::Formatter) -> ::std::fmt::Result {
                for i in self[..].iter().cloned() {
                    (write!(f, "{:02x}", i))?;
                }
                Ok(())
            }
        }
    };
}
