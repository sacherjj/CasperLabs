use alloc::{
    collections::{BTreeMap, BTreeSet},
    string::String,
};
use core::fmt::Write;

use contract::contract_api::runtime;
use proof_of_stake::{Stakes, StakesProvider};
use types::{
    account::PublicKey,
    system_contract_errors::pos::{Error, Result},
    Key, U512,
};

/// A `StakesProvider` that reads and writes the stakes to/from the contract's
/// known urefs.
pub struct ContractStakes;

impl StakesProvider for ContractStakes {
    /// Reads the current stakes from the contract's known urefs.
    fn read() -> Result<Stakes> {
        let mut stakes = BTreeMap::new();
        for (name, _) in runtime::list_named_keys() {
            let mut split_name = name.split('_');
            if Some("v") != split_name.next() {
                continue;
            }
            let hex_key = split_name
                .next()
                .ok_or(Error::StakesKeyDeserializationFailed)?;
            if hex_key.len() != 64 {
                return Err(Error::StakesKeyDeserializationFailed);
            }
            let mut key_bytes = [0u8; 32];
            let _bytes_written = base16::decode_slice(hex_key, &mut key_bytes)
                .map_err(|_| Error::StakesKeyDeserializationFailed)?;
            debug_assert!(_bytes_written == key_bytes.len());
            let pub_key = PublicKey::from_ed25519_bytes(key_bytes);
            let balance = split_name
                .next()
                .and_then(|b| U512::from_dec_str(b).ok())
                .ok_or(Error::StakesDeserializationFailed)?;
            stakes.insert(pub_key, balance);
        }
        if stakes.is_empty() {
            return Err(Error::StakesNotFound);
        }
        Ok(Stakes(stakes))
    }

    /// Writes the current stakes to the contract's known urefs.
    fn write(stakes: &Stakes) {
        // Encode the stakes as a set of uref names.
        let mut new_urefs: BTreeSet<String> = stakes
            .0
            .iter()
            .map(|(pub_key, balance)| {
                let key_bytes = pub_key.as_bytes();
                let hex_key = base16::encode_lower(&key_bytes);
                let mut uref = String::new();
                uref.write_fmt(format_args!("v_{}_{}", hex_key, balance))
                    .expect("Writing to a string cannot fail");
                uref
            })
            .collect();
        // Remove and add urefs to update the contract's known urefs accordingly.
        for (name, _) in runtime::list_named_keys() {
            if name.starts_with("v_") && !new_urefs.remove(&name) {
                runtime::remove_key(&name);
            }
        }
        for name in new_urefs {
            runtime::put_key(&name, Key::Hash([0; 32]));
        }
    }
}
