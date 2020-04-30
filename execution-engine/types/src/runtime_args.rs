//! Home of RuntimeArgs for calling contracts

use alloc::{collections::BTreeMap, string::String, vec::Vec};

use crate::{
    bytesrepr::{self, Error, FromBytes, ToBytes, U32_SERIALIZED_LENGTH},
    CLTyped, CLValue,
};

/// Named arguments to a contract
#[derive(PartialEq, Eq, Clone, Debug)]
pub struct Named(String, CLValue);

impl Named {
    pub fn name(&self) -> &str {
        &self.0
    }
    pub fn cl_value(&self) -> &CLValue {
        &self.1
    }
}

impl From<(String, CLValue)> for Named {
    fn from((name, value): (String, CLValue)) -> Named {
        Named(name, value)
    }
}

/// Represents a collection of arguments passed to a smart contract.
#[derive(PartialEq, Eq, Clone, Debug)]
pub enum RuntimeArgs {
    /// Contains ordered, positional arguments.
    Positional(Vec<CLValue>),
    /// Contains ordered named arguments.
    Named(Vec<Named>),
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
            RuntimeArgs::Named(values) => values.get(index).map(|Named(_key, value)| value),
        }
    }

    /// Gets an argument by its name.
    pub fn get(&self, name: &str) -> Option<&CLValue> {
        match self {
            RuntimeArgs::Named(values) => values
                .iter()
                .filter_map(|Named(named_name, named_value)| {
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
                values.push(Named(key.into(), cl_value));
            }
        }
    }

    /// Returns values held regardless of the variant.
    pub fn to_values(&self) -> Vec<&CLValue> {
        match self {
            RuntimeArgs::Positional(values) => values.iter().collect(),
            RuntimeArgs::Named(named_values) => named_values
                .iter()
                .map(|Named(_name, value)| value)
                .collect(),
        }
    }
}

impl From<Vec<CLValue>> for RuntimeArgs {
    fn from(clvalues: Vec<CLValue>) -> Self {
        // Temporary compatibility with positional arguments
        RuntimeArgs::Positional(clvalues)
    }
}

impl From<Vec<Named>> for RuntimeArgs {
    fn from(values: Vec<Named>) -> Self {
        RuntimeArgs::Named(values)
    }
}

impl From<BTreeMap<String, CLValue>> for RuntimeArgs {
    fn from(clvalues: BTreeMap<String, CLValue>) -> RuntimeArgs {
        // Temporary compatibility with positional arguments
        RuntimeArgs::Named(clvalues.into_iter().map(Named::from).collect())
    }
}

impl ToBytes for RuntimeArgs {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = bytesrepr::allocate_buffer(self)?;

        let num_keys = self.len() as u32;
        result.append(&mut num_keys.to_bytes()?);

        match self {
            RuntimeArgs::Positional(values) => {
                for value in values {
                    result.append(&mut value.to_bytes()?);
                }
            }
            RuntimeArgs::Named(named_values) => {
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
            RuntimeArgs::Positional(values) => values.serialized_length(),
            RuntimeArgs::Named(named_values) => {
                U32_SERIALIZED_LENGTH
                    + named_values
                        .iter()
                        .map(|Named(name, value)| {
                            name.serialized_length() + value.serialized_length()
                        })
                        .sum::<usize>()
            }
        }
    }
}

impl FromBytes for RuntimeArgs {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (num_keys, mut stream) = u32::from_bytes(bytes)?;
        // let mut result = RuntimeArgs::new();
        let mut named_args = Vec::with_capacity(num_keys as usize);
        for _ in 0..num_keys {
            let (k, rem) = String::from_bytes(stream)?;
            let (v, rem) = CLValue::from_bytes(rem)?;
            named_args.push(Named(k, v));
            stream = rem;
        }
        Ok((named_args.into(), stream))
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
        let mut runtime_args_2 = BTreeMap::new();
        runtime_args_2.insert(String::from("bar"), CLValue::from_t("Foo").unwrap());
        runtime_args_2.insert(String::from("foo"), CLValue::from_t(1i32).unwrap());
        runtime_args_2.insert(String::from("qwer"), CLValue::from_t(Some(1i32)).unwrap());

        assert_eq!(
            runtime_args_1.to_bytes().unwrap(),
            runtime_args_2.to_bytes().unwrap()
        );
    }
}
