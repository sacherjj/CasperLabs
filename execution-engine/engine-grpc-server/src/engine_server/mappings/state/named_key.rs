use std::{
    collections::BTreeMap,
    convert::{TryFrom, TryInto},
};

use contract_ffi::key::Key;

use crate::engine_server::{mappings::ParsingError, state::NamedKey as ProtobufNamedKey};

impl From<(String, Key)> for ProtobufNamedKey {
    fn from((name, key): (String, Key)) -> Self {
        let mut pb_named_key = ProtobufNamedKey::new();
        pb_named_key.set_name(name);
        pb_named_key.set_key(key.into());
        pb_named_key
    }
}

impl TryFrom<ProtobufNamedKey> for (String, Key) {
    type Error = ParsingError;

    fn try_from(mut pb_named_key: ProtobufNamedKey) -> Result<Self, Self::Error> {
        let key = pb_named_key.take_key().try_into()?;
        let name = pb_named_key.name;
        Ok((name, key))
    }
}

/// Thin wrapper to allow us to implement `From` and `TryFrom` helpers to convert to and from
/// `BTreeMap<String, Key>` and `Vec<ProtobufNamedKey>`.
#[derive(Clone, PartialEq, Debug)]
pub(crate) struct NamedKeyMap(BTreeMap<String, Key>);

impl NamedKeyMap {
    pub fn new(inner: BTreeMap<String, Key>) -> Self {
        Self(inner)
    }

    pub fn into_inner(self) -> BTreeMap<String, Key> {
        self.0
    }
}

impl From<NamedKeyMap> for Vec<ProtobufNamedKey> {
    fn from(named_key_map: NamedKeyMap) -> Self {
        named_key_map.0.into_iter().map(Into::into).collect()
    }
}

impl TryFrom<Vec<ProtobufNamedKey>> for NamedKeyMap {
    type Error = ParsingError;

    fn try_from(pb_named_keys: Vec<ProtobufNamedKey>) -> Result<Self, Self::Error> {
        let mut named_key_map = NamedKeyMap(BTreeMap::new());
        for pb_named_key in pb_named_keys {
            let (name, key) = pb_named_key.try_into()?;
            // TODO - consider returning an error if `insert()` returns `Some`.
            let _ = named_key_map.0.insert(name, key);
        }
        Ok(named_key_map)
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use contract_ffi::gens;

    use super::*;
    use crate::engine_server::mappings::test_utils;

    proptest! {
        #[test]
        fn round_trip(string in "\\PC*", key in gens::key_arb()) {
            test_utils::protobuf_round_trip::<(String, Key), ProtobufNamedKey>((string, key));
        }

        #[test]
        fn map_round_trip(named_keys in gens::named_keys_arb(10)) {
            let named_key_map = NamedKeyMap(named_keys.clone());
            test_utils::protobuf_round_trip::<NamedKeyMap, Vec<ProtobufNamedKey>>(named_key_map);
        }
    }
}
