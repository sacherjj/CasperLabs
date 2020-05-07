//! Home of RuntimeArgs for calling contracts

use alloc::{collections::BTreeMap, string::String, vec::Vec};

use crate::{
    bytesrepr::{self, Error, FromBytes, ToBytes, U32_SERIALIZED_LENGTH},
    CLTyped, CLValue,
};
use core::convert::TryFrom;

/// Named arguments to a contract
#[derive(PartialEq, Eq, Clone, Debug)]
pub struct NamedArg(String, CLValue);

impl NamedArg {
    /// ctor
    pub fn new(name: String, value: CLValue) -> Self {
        NamedArg(name, value)
    }
    /// returns `name`
    pub fn name(&self) -> &str {
        &self.0
    }
    /// returns `value`
    pub fn cl_value(&self) -> &CLValue {
        &self.1
    }
}

impl From<(String, CLValue)> for NamedArg {
    fn from((name, value): (String, CLValue)) -> NamedArg {
        NamedArg(name, value)
    }
}

#[repr(u8)]
enum Tag {
    Positional = 0,
    Named = 1,
}

/// Represents a collection of arguments passed to a smart contract.
#[derive(PartialEq, Eq, Clone, Debug)]
pub enum RuntimeArgs {
    /// Contains ordered, positional arguments.
    Positional(Vec<CLValue>),
    /// Contains ordered named arguments.
    Named(Vec<NamedArg>),
}

impl Default for RuntimeArgs {
    fn default() -> Self {
        RuntimeArgs::Named(Vec::new())
    }
}

impl RuntimeArgs {
    /// Create an empty [`RuntimeArgs`] instance.
    pub fn new() -> RuntimeArgs {
        RuntimeArgs::default()
    }

    /// Gets a positional argument by its index.
    ///
    /// This method exists just for backwards compatibility.
    pub fn get_positional(&self, index: usize) -> Option<&CLValue> {
        // Temporary compatibility with `get_arg`
        match self {
            RuntimeArgs::Positional(values) => values.get(index),
            RuntimeArgs::Named(values) => values.get(index).map(|NamedArg(_key, value)| value),
        }
    }

    /// Gets an argument by its name.
    pub fn get(&self, name: &str) -> Option<&CLValue> {
        match self {
            RuntimeArgs::Named(values) => values
                .iter()
                .filter_map(|NamedArg(named_name, named_value)| {
                    if named_name == name {
                        Some(named_value)
                    } else {
                        None
                    }
                })
                .next(),
            _ => None,
        }
    }

    /// Get length of the collection.
    pub fn len(&self) -> usize {
        match self {
            RuntimeArgs::Positional(values) => values.len(),
            RuntimeArgs::Named(values) => values.len(),
        }
    }

    /// Check if collection of arguments is empty.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Insert new named argument into the collection.
    pub fn insert<K, V>(&mut self, key: K, value: V)
    where
        K: Into<String>,
        V: CLTyped + ToBytes,
    {
        match self {
            RuntimeArgs::Positional(_) => {
                panic!("Unsupported operation: inserting named argument into positional arguments")
            }
            RuntimeArgs::Named(values) => {
                let cl_value = CLValue::from_t(value).expect("should create CLValue");
                values.push(NamedArg(key.into(), cl_value));
            }
        }
    }

    /// Returns values held regardless of the variant.
    pub fn to_values(&self) -> Vec<&CLValue> {
        match self {
            RuntimeArgs::Positional(values) => values.iter().collect(),
            RuntimeArgs::Named(named_values) => named_values
                .iter()
                .map(|NamedArg(_name, value)| value)
                .collect(),
        }
    }
}

impl From<Vec<CLValue>> for RuntimeArgs {
    fn from(cl_values: Vec<CLValue>) -> Self {
        // Temporary compatibility with positional arguments
        RuntimeArgs::Positional(cl_values)
    }
}

impl From<Vec<NamedArg>> for RuntimeArgs {
    fn from(values: Vec<NamedArg>) -> Self {
        RuntimeArgs::Named(values)
    }
}

impl From<BTreeMap<String, CLValue>> for RuntimeArgs {
    fn from(cl_values: BTreeMap<String, CLValue>) -> RuntimeArgs {
        RuntimeArgs::Named(cl_values.into_iter().map(NamedArg::from).collect())
    }
}

impl TryFrom<RuntimeArgs> for BTreeMap<String, CLValue> {
    type Error = &'static str;

    fn try_from(runtime_args: RuntimeArgs) -> Result<Self, Self::Error> {
        match runtime_args {
            RuntimeArgs::Positional(_) => Err("Positional RuntimeArgs not compatible"),
            RuntimeArgs::Named(items) => {
                let mut map = BTreeMap::new();
                for named in items {
                    map.insert(named.0, named.1);
                }
                Ok(map)
            }
        }
    }
}

impl ToBytes for RuntimeArgs {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = bytesrepr::allocate_buffer(self)?;

        match self {
            RuntimeArgs::Positional(values) => {
                result.push(Tag::Positional as u8);
                let num_values = values.len() as u32;
                result.append(&mut num_values.to_bytes()?);
                for cl_value in values {
                    result.append(&mut cl_value.to_bytes()?);
                }
            }
            RuntimeArgs::Named(named_values) => {
                result.push(Tag::Named as u8);
                let num_keys = named_values.len() as u32;
                result.append(&mut num_keys.to_bytes()?);
                for named_value in named_values {
                    result.append(&mut named_value.name().to_bytes()?);
                    result.append(&mut named_value.cl_value().to_bytes()?);
                }
            }
        }
        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        match self {
            RuntimeArgs::Positional(values) => values.serialized_length() + 1,
            RuntimeArgs::Named(named_values) => {
                U32_SERIALIZED_LENGTH
                    + 1
                    + named_values
                        .iter()
                        .map(|NamedArg(name, value)| {
                            name.serialized_length() + value.serialized_length()
                        })
                        .sum::<usize>()
            }
        }
    }
}

impl FromBytes for RuntimeArgs {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (tag, remainder): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match tag {
            tag if tag == Tag::Positional as u8 => {
                let (num_values, mut stream) = u32::from_bytes(remainder)?;
                let mut positional_args = Vec::with_capacity(num_values as usize);
                for _ in 0..num_values {
                    let (cl_value, remainder) = CLValue::from_bytes(stream)?;
                    positional_args.push(cl_value);
                    stream = remainder;
                }
                debug_assert!(stream.is_empty(), "stream should be empty {:?}", stream);
                Ok((positional_args.into(), stream))
            }
            tag if tag == Tag::Named as u8 => {
                let (num_keys, mut stream) = u32::from_bytes(remainder)?;
                let mut named_args = Vec::with_capacity(num_keys as usize);
                for _ in 0..num_keys {
                    let (k, remainder) = String::from_bytes(stream)?;
                    let (v, remainder) = CLValue::from_bytes(remainder)?;
                    named_args.push(NamedArg(k, v));
                    stream = remainder;
                }
                debug_assert!(stream.is_empty(), "stream should be empty {:?}", stream);
                Ok((named_args.into(), stream))
            }
            _ => Err(bytesrepr::Error::Formatting),
        }
    }
}

/// Macro that makes it easier to construct named arguments.
///
/// # Example usage
/// ```
/// use casperlabs_types::{RuntimeArgs, runtime_args};
/// let _named_args = runtime_args! {
///   "foo" => 42,
///   "bar" => "Hello, world!"
/// };
/// ```
#[macro_export]
macro_rules! runtime_args {
    () => (RuntimeArgs::new());
    ( $($key:expr => $value:expr,)+ ) => (runtime_args!($($key => $value),+));
    ( $($key:expr => $value:expr),* ) => {
        {
            let mut named_args = RuntimeArgs::new();
            $(
                named_args.insert($key, $value);
            )*
            named_args
        }
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_runtime_args_positional_compatibility() {
        let arg1 = CLValue::from_t(1).unwrap();
        let arg2 = CLValue::from_t("Foo").unwrap();
        let arg3 = CLValue::from_t(Some(1)).unwrap();
        let vec = vec![arg1.clone(), arg2.clone(), arg3.clone()];
        let runtime_args = RuntimeArgs::from(vec);
        assert_eq!(runtime_args.get_positional(0), Some(&arg1));
        assert_eq!(runtime_args.get_positional(1), Some(&arg2));
        assert_eq!(runtime_args.get_positional(2), Some(&arg3));
        assert_eq!(runtime_args.get_positional(3), None);
        assert_eq!(runtime_args.get("0"), None);
    }

    #[test]
    fn test_runtime_args() {
        let arg1 = CLValue::from_t(1).unwrap();
        let arg2 = CLValue::from_t("Foo").unwrap();
        let arg3 = CLValue::from_t(Some(1)).unwrap();
        let args = {
            let mut map = BTreeMap::new();
            map.insert("bar".into(), arg2.clone());
            map.insert("foo".into(), arg1.clone());
            map.insert("qwer".into(), arg3.clone());
            map
        };
        let runtime_args = RuntimeArgs::from(args);
        assert_eq!(runtime_args.get("qwer"), Some(&arg3));
        assert_eq!(runtime_args.get("foo"), Some(&arg1));
        assert_eq!(runtime_args.get("bar"), Some(&arg2));
        assert_eq!(runtime_args.get("aaa"), None);

        // Ordered by key
        assert_eq!(runtime_args.get_positional(0), Some(&arg2));
        assert_eq!(runtime_args.get_positional(1), Some(&arg1));
        assert_eq!(runtime_args.get_positional(2), Some(&arg3));
        assert_eq!(runtime_args.get_positional(3), None);

        // Ensure macro works

        let runtime_args_2 = runtime_args! {
            "bar" => "Foo",
            "foo" => 1i32,
            "qwer" => Some(1i32),
        };
        assert_eq!(runtime_args, runtime_args_2);
    }

    #[test]
    fn empty_macro() {
        assert_eq!(runtime_args! {}, RuntimeArgs::new());
    }

    #[test]
    fn btreemap_compat() {
        // This test assumes same serialization format as BTreeMap
        let runtime_args_1 = runtime_args! {
            "bar" => "Foo",
            "foo" => 1i32,
            "qwer" => Some(1i32),
        };
        let tagless = runtime_args_1.to_bytes().unwrap()[1..].to_vec();

        let mut runtime_args_2 = BTreeMap::new();
        runtime_args_2.insert(String::from("bar"), CLValue::from_t("Foo").unwrap());
        runtime_args_2.insert(String::from("foo"), CLValue::from_t(1i32).unwrap());
        runtime_args_2.insert(String::from("qwer"), CLValue::from_t(Some(1i32)).unwrap());

        assert_eq!(tagless, runtime_args_2.to_bytes().unwrap());
    }

    #[test]
    fn named_serialization_roundtrip() {
        let args = runtime_args! {
            "foo" => 1i32,
        };
        bytesrepr::test_serialization_roundtrip(&args);
    }

    #[test]
    fn positional_serialization_roundtrip() {
        let cl_value = CLValue::from_t(8i32).expect("should convert");
        let args = RuntimeArgs::Positional(vec![cl_value]);
        bytesrepr::test_serialization_roundtrip(&args);
    }
}
