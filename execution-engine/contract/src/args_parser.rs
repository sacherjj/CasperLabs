//! Home of [`ArgsParser`], a trait used for parsing contract arguments from n-ary tuples.

// Can be removed once https://github.com/rust-lang/rustfmt/issues/3362 is resolved.
#[rustfmt::skip]
use alloc::vec;

use casperlabs_types::{bytesrepr::ToBytes, CLTyped, CLValue, CLValueError, RuntimeArgs};

/// Types which implement [`ArgsParser`] can be parsed into an ABI-compliant byte representation
/// suitable for passing as arguments to a contract.
///
/// It is primarily implemented for n-ary tuples of values which themselves implement [`ToBytes`]
/// and [`CLTyped`].
pub trait ArgsParser {
    /// Parses the arguments to a `Vec` of [`CLValue`]s.
    fn parse(self) -> Result<RuntimeArgs, CLValueError>;
}

impl ArgsParser for () {
    fn parse(self) -> Result<RuntimeArgs, CLValueError> {
        Ok(RuntimeArgs::new())
    }
}

impl ArgsParser for RuntimeArgs {
    fn parse(self) -> Result<RuntimeArgs, CLValueError> {
        Ok(self)
    }
}

macro_rules! impl_argsparser_tuple {
    ( $($name:ident)+) => (
        impl<$($name: CLTyped + ToBytes),*> ArgsParser for ($($name,)*) {
            #[allow(non_snake_case)]
            fn parse(self) -> Result<RuntimeArgs, CLValueError> {
                let ($($name,)+) = self;
                let args = vec![$(CLValue::from_t($name)?,)+];
                Ok(args.into())
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
