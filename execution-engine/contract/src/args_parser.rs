//! Home of [`ArgsParser`](crate::args_parser::ArgsParser), a trait used for parsing contract
//! arguments from n-ary tuples.

// Can be removed once https://github.com/rust-lang/rustfmt/issues/3362 is resolved.
#[rustfmt::skip]
use alloc::vec;
use alloc::vec::Vec;

use casperlabs_types::{
    bytesrepr::{Error, ToBytes},
    CLTyped, CLValue, CLValueError,
};

/// Types which implement [`ArgsParser`] can be parsed into an ABI-compliant byte representation
/// suitable for passing as arguments to a contract.
///
/// It is primarily implemented for n-ary tuples of values which themselves implement [`ToBytes`]
/// and [`CLTyped`].
pub trait ArgsParser {
    fn parse(self) -> Result<Vec<CLValue>, CLValueError>;

    #[doc(hidden)]
    /// This parses the args to a `Vec<Vec<u8>` so that we can continue to support this form being
    /// received from Node in Deploy requests.  Once Node has been altered to support `CLValue`
    /// fully, we can remove this method and receive args as serialized `Vec<CLValue>`.
    fn parse_to_vec_u8(self) -> Result<Vec<Vec<u8>>, Error>;
}

impl ArgsParser for () {
    fn parse(self) -> Result<Vec<CLValue>, CLValueError> {
        Ok(Vec::new())
    }

    fn parse_to_vec_u8(self) -> Result<Vec<Vec<u8>>, Error> {
        Ok(Vec::new())
    }
}

macro_rules! impl_argsparser_tuple {
    ( $($name:ident)+) => (
        impl<$($name: CLTyped + ToBytes),*> ArgsParser for ($($name,)*) {
            #[allow(non_snake_case)]
            fn parse(self) -> Result<Vec<CLValue>, CLValueError> {
                let ($($name,)+) = self;
                Ok(vec![$(CLValue::from_t($name)?,)+])
            }

            #[allow(non_snake_case)]
            fn parse_to_vec_u8(self) -> Result<Vec<Vec<u8>>, Error> {
                let ($($name,)+) = self;
                Ok(vec![$(ToBytes::into_bytes($name)?,)+])
            }
        }
    );
}

impl_argsparser_tuple! { T1 }
impl_argsparser_tuple! { T1 T2 }
impl_argsparser_tuple! { T1 T2 T3 }
impl_argsparser_tuple! { T1 T2 T3 T4 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 T9 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 T9 T10 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 T9 T10 T11 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 T9 T10 T11 T12 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 T9 T10 T11 T12 T13 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 T9 T10 T11 T12 T13 T14 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 T9 T10 T11 T12 T13 T14 T15 }
impl_argsparser_tuple! { T1 T2 T3 T4 T5 T6 T7 T8 T9 T10 T11 T12 T13 T14 T15 T16 }
