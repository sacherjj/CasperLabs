use std::convert::{TryFrom, TryInto};

use contract_ffi::key::Key;

use crate::engine_server::{
    mappings::{self, ParsingError},
    state::{
        Key as ProtobufKey, Key_Address as ProtobufAccount, Key_Hash as ProtobufHash,
        Key_Local as ProtobufLocal, Key_oneof_value as ProtobufKeyEnum,
    },
};

impl From<Key> for ProtobufKey {
    fn from(key: Key) -> Self {
        let mut pb_key = ProtobufKey::new();
        match key {
            Key::Account(account) => {
                let mut pb_account = ProtobufAccount::new();
                pb_account.set_account(account.to_vec());
                pb_key.set_address(pb_account);
            }
            Key::Hash(hash) => {
                let mut pb_hash = ProtobufHash::new();
                pb_hash.set_hash(hash.to_vec());
                pb_key.set_hash(pb_hash);
            }
            Key::URef(uref) => {
                pb_key.set_uref(uref.into());
            }
            Key::Local(hash) => {
                let mut pb_local = ProtobufLocal::new();
                pb_local.set_hash(hash.to_vec());
                pb_key.set_local(pb_local);
            }
        }
        pb_key
    }
}

impl TryFrom<ProtobufKey> for Key {
    type Error = ParsingError;

    fn try_from(pb_key: ProtobufKey) -> Result<Self, Self::Error> {
        let pb_key = pb_key
            .value
            .ok_or_else(|| ParsingError::from("Unable to parse Protobuf Key"))?;

        let key = match pb_key {
            ProtobufKeyEnum::address(pb_account) => {
                let account = mappings::vec_to_array(pb_account.account, "Protobuf Key::Account")?;
                Key::Account(account)
            }
            ProtobufKeyEnum::hash(pb_hash) => {
                let hash = mappings::vec_to_array(pb_hash.hash, "Protobuf Key::Hash")?;
                Key::Hash(hash)
            }
            ProtobufKeyEnum::uref(pb_uref) => {
                let uref = pb_uref.try_into()?;
                Key::URef(uref)
            }
            ProtobufKeyEnum::local(pb_local) => {
                let local = mappings::vec_to_array(pb_local.hash, "Protobuf Key::Local")?;
                Key::Local(local)
            }
        };
        Ok(key)
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
        fn round_trip(key in gens::key_arb()) {
            test_utils::protobuf_round_trip::<Key, ProtobufKey>(key);
        }
    }
}
