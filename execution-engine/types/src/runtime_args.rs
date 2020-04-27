//! Home of RuntimeArgs for calling contracts

use alloc::{collections::BTreeMap, string::String, vec::Vec};

use crate::{
    bytesrepr::{self, Error, FromBytes, ToBytes, U32_SERIALIZED_LENGTH},
    CLTyped, CLValue,
};

/// Named arguments to a contract
#[derive(PartialEq, Eq, Clone, Debug)]
pub struct Named(String, CLValue);

/// An enum that represents a runtime argument.
#[derive(PartialEq, Eq, Clone, Debug)]
pub enum RuntimeArg {
    /// Positional argument
    Positional(CLValue),
    /// Named argument
    Named(Named),
}

impl RuntimeArg {
    /// Gets a reference to a named argument.
    fn as_named(&self) -> Option<&Named> {
        match self {
            RuntimeArg::Named(named) => Some(&named),
            _ => None,
        }
    }
    /// Gets a reference to a value regardless of the variant
    pub fn cl_value(&self) -> &CLValue {
        match self {
            RuntimeArg::Positional(value) => &value,
            RuntimeArg::Named(Named(_name, value)) => &value,
        }
    }
}

impl ToBytes for RuntimeArgs {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = bytesrepr::allocate_buffer(self)?;

        let num_keys = self.len() as u32;
        result.append(&mut num_keys.to_bytes()?);

        for runtime_arg in self.iter() {
            match runtime_arg {
                RuntimeArg::Positional(value) => result.append(&mut value.to_bytes()?),
                RuntimeArg::Named(Named(name, value)) => {
                    result.append(&mut name.to_bytes()?);
                    result.append(&mut value.to_bytes()?);
                }
            };
        }

        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        U32_SERIALIZED_LENGTH
            + self
                .iter()
                .map(|runtime_arg| match runtime_arg {
                    RuntimeArg::Positional(value) => value.serialized_length(),
                    RuntimeArg::Named(Named(name, value)) => {
                        name.serialized_length() + value.serialized_length()
                    }
                })
                .sum::<usize>()
    }
}

impl FromBytes for RuntimeArgs {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (num_keys, mut stream) = u32::from_bytes(bytes)?;
        let mut result = RuntimeArgs::new();
        for _ in 0..num_keys {
            let (k, rem) = String::from_bytes(stream)?;
            let (v, rem) = CLValue::from_bytes(rem)?;
            result.0.push(RuntimeArg::Named(Named(k, v)));
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl From<(String, CLValue)> for RuntimeArg {
    fn from((name, value): (String, CLValue)) -> RuntimeArg {
        RuntimeArg::Named(Named(name, value))
    }
}

/// Represents a collection of arguments passed to a smart contract.
#[derive(Default, PartialEq, Eq, Clone, Debug)]
pub struct RuntimeArgs(Vec<RuntimeArg>);

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
        self.0.get(index).map(RuntimeArg::cl_value)
    }

    /// Gets an argument by its name.
    pub fn get(&self, name: &str) -> Option<&CLValue> {
        self.0
            .iter()
            .filter_map(RuntimeArg::as_named)
            .find(|named_arg| named_arg.0 == name)
            .map(|named_arg| &named_arg.1)
    }

    /// Iterate over [`CLValue`]s.
    pub fn iter(&self) -> impl Iterator<Item = &RuntimeArg> {
        self.0.iter()
    }

    /// Iterate over values.
    pub fn values(&self) -> impl Iterator<Item = &CLValue> {
        self.0.iter().map(|named_arg| named_arg.cl_value())
    }

    /// Get length of the collection.
    pub fn len(&self) -> usize {
        self.0.len()
    }

    /// Check if collection of arguments is empty.
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Insert new named argument into the collection.
    pub fn insert<K, V>(&mut self, key: K, value: V)
    where
        K: Into<String>,
        V: CLTyped + ToBytes,
    {
        let runtime_arg = (
            key.into(),
            CLValue::from_t(value).expect("should create CLValue"),
        )
            .into();
        self.0.push(runtime_arg);
    }
}

impl From<Vec<CLValue>> for RuntimeArgs {
    fn from(clvalues: Vec<CLValue>) -> RuntimeArgs {
        // Temporary compatibility with positional arguments
        RuntimeArgs(clvalues.into_iter().map(Into::into).collect())
    }
}

impl From<BTreeMap<String, CLValue>> for RuntimeArgs {
    fn from(clvalues: BTreeMap<String, CLValue>) -> RuntimeArgs {
        // Temporary compatibility with positional arguments
        RuntimeArgs(clvalues.into_iter().map(Into::into).collect())
    }
}

impl From<CLValue> for RuntimeArg {
    fn from(value: CLValue) -> RuntimeArg {
        RuntimeArg::Positional(value)
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
            map.insert("bar".to_owned(), arg2.clone());
            map.insert("foo".to_owned(), arg1.clone());
            map.insert("qwer".to_owned(), arg3.clone());
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
        runtime_args_2.insert("bar".to_owned(), CLValue::from_t("Foo").unwrap());
        runtime_args_2.insert("foo".to_owned(), CLValue::from_t(1i32).unwrap());
        runtime_args_2.insert("qwer".to_owned(), CLValue::from_t(Some(1i32)).unwrap());

        assert_eq!(
            runtime_args_1.to_bytes().unwrap(),
            runtime_args_2.to_bytes().unwrap()
        );
    }
}
