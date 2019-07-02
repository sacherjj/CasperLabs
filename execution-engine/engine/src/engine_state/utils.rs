use parity_wasm::elements::Serialize;

use common::key::addr_to_hex;
use common::value::account::PublicKey;
use common::value::U512;
use engine_state;
use wasm_prep::{Preprocessor, WasmiPreprocessor};
use wasm_prep::wasm_costs::WasmCosts;

#[derive(Debug, Clone)]
pub struct WasmiBytes(Vec<u8>);

impl WasmiBytes {
    pub fn new(raw_bytes: &[u8], wasm_costs: WasmCosts) -> Result<Self, engine_state::Error> {
        let mut ret = vec![];
        let wasmi_preprocessor: WasmiPreprocessor = WasmiPreprocessor::new(wasm_costs);
        let module = wasmi_preprocessor.preprocess(raw_bytes)?;
        module.serialize(&mut ret)?;
        Ok(WasmiBytes(ret))
    }
}

impl Into<Vec<u8>> for WasmiBytes {
    fn into(self) -> Vec<u8> {
        self.0
    }
}

/// Helper function to create validator labels as they are constructed in PoS.
pub fn pos_validator_key(pk: PublicKey, stakes: U512) -> String {
    let public_key_hex: String = addr_to_hex(&pk.value());
    // This is how PoS contract stores validator keys in its known_urefs map.
    format!("v_{}_{}", public_key_hex, stakes)
}

/// Dual of `pos_validator_key`. Parses PoS bond format to PublicKey, U512 pair.
pub fn pos_validator_to_tuple(pos_bond: &str) -> Option<(PublicKey, U512)> {
    let mut split_bond = pos_bond.split('_'); // expected format is "v_{public_key}_{bond}".
    if Some("v") != split_bond.next() {
        None
    } else {
        let hex_key: &str = split_bond.next()?;
        if hex_key.len() != 64 {
            return None;
        }
        let mut key_bytes = [0u8; 32];
        for i in 0..32 {
            key_bytes[i] = u8::from_str_radix(&hex_key[2 * i..2 * (i + 1)], 16).ok()?;
        }
        let pub_key = PublicKey::new(key_bytes);
        let balance = split_bond.next().and_then(|b| U512::from_dec_str(b).ok())?;
        Some((pub_key, balance))
    }
}

#[cfg(test)]
mod tests {
    use common::key::addr_to_hex;
    use common::value::account::PublicKey;
    use common::value::U512;

    use super::{pos_validator_key, pos_validator_to_tuple};

    #[test]
    fn should_to_string_pos_validator() {
        let public_key = PublicKey::new([1u8; 32]);
        let hex_public_key = addr_to_hex(&public_key.value());
        let stake = U512::from(100);
        let expected = format!("v_{}_{}", hex_public_key, stake);
        assert_eq!(pos_validator_key(public_key, stake), expected);
    }

    #[test]
    fn should_parse_string_to_validator_tuple() {
        let public_key = PublicKey::new([1u8; 32]);
        let hex_public_key = addr_to_hex(&public_key.value());
        let stake = U512::from(100);
        let strng = format!("v_{}_{}", hex_public_key, stake);

        let parsed = pos_validator_to_tuple(&strng);
        assert!(parsed.is_some());
        let (parsed_pk, parsed_stake) = parsed.unwrap();
        assert_eq!(parsed_pk, public_key);
        assert_eq!(parsed_stake, stake);
    }

    #[test]
    fn should_not_parse_string_to_validator_tuple() {
        let not_validator_stake = "v_10_ab".to_string();
        assert!(pos_validator_to_tuple(&not_validator_stake).is_none());
    }
}
