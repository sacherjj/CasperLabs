use crate::bytesrepr;
use alloc::vec::Vec;
use bytesrepr::{Error, ToBytes};

pub trait ArgsParser {
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error>;
}

macro_rules! impl_argsparser_tuple {
    ( $name:ident ) => (
        impl<$name: ToBytes> ArgsParser for $name {
            #[allow(non_snake_case)]
            fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
                let $name = self;
                Ok(vec![ToBytes::to_bytes($name)?])
            }
        }
    );
    ( $($name:ident)+) => (
        impl<$($name: ToBytes),*> ArgsParser for ($($name,)*) {
            #[allow(non_snake_case)]
            fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
                let ($(ref $name,)+) = *self;
                Ok(vec![$(ToBytes::to_bytes($name)?,)+])
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
