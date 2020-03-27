use alloc::{format, string::String, vec::Vec};
use core::fmt::{self, Debug, Display, Formatter};

use blake2::{
    digest::{Input, VariableOutput},
    VarBlake2b,
};
use hex_fmt::HexFmt;

use crate::{
    account::PublicKey,
    bytesrepr::{self, Error, FromBytes, ToBytes},
    AccessRights, ContractRef, URef, UREF_SERIALIZED_LENGTH,
};

const ACCOUNT_ID: u8 = 0;
const HASH_ID: u8 = 1;
const UREF_ID: u8 = 2;
const LOCAL_ID: u8 = 3;

/// The number of bytes in a Blake2b hash
pub const BLAKE2B_DIGEST_LENGTH: usize = 32;
/// The number of bytes in a [`Key::Hash`].
pub const KEY_HASH_LENGTH: usize = 32;
/// The number of bytes in a [`Key::Local`].
pub const KEY_LOCAL_LENGTH: usize = 64;
/// The number of bytes in the seed for a new [`Key::Local`].
pub const KEY_LOCAL_SEED_LENGTH: usize = 32;

const KEY_ID_SERIALIZED_LENGTH: usize = 1; // u8 used to determine the ID
const KEY_HASH_SERIALIZED_LENGTH: usize = KEY_ID_SERIALIZED_LENGTH + KEY_HASH_LENGTH;
/// Number of bytes taken by a serialized `Key::URef`
/// TODO: decide if this should be public once contract::storage::new_turef is removed
pub const KEY_UREF_SERIALIZED_LENGTH: usize = KEY_ID_SERIALIZED_LENGTH + UREF_SERIALIZED_LENGTH;
const KEY_LOCAL_SERIALIZED_LENGTH: usize =
    KEY_ID_SERIALIZED_LENGTH + KEY_LOCAL_SEED_LENGTH + BLAKE2B_DIGEST_LENGTH;

/// Creates a 32-byte BLAKE2b hash digest from a given a piece of data
fn hash(bytes: &[u8]) -> [u8; BLAKE2B_DIGEST_LENGTH] {
    let mut ret = [0u8; BLAKE2B_DIGEST_LENGTH];
    // Safe to unwrap here because our digest length is constant and valid
    let mut hasher = VarBlake2b::new(BLAKE2B_DIGEST_LENGTH).unwrap();
    hasher.input(bytes);
    hasher.variable_result(|hash| ret.clone_from_slice(hash));
    ret
}

/// The type under which data (e.g. [`CLValue`](crate::CLValue)s, smart contracts, user accounts)
/// are indexed on the network.
#[repr(C)]
#[derive(PartialEq, Eq, Clone, Copy, PartialOrd, Ord, Hash)]
pub enum Key {
    /// A `Key` under which a user account is stored.
    Account(PublicKey),
    /// A `Key` under which a smart contract is stored and which is the pseudo-hash of the
    /// contract.
    Hash([u8; KEY_HASH_LENGTH]),
    /// A `Key` which is a [`URef`], under which most types of data can be stored.
    URef(URef),
    /// A `Key` to data (normally a [`CLValue`](crate::CLValue)) which is held in local-storage
    /// rather than global-storage.
    Local {
        /// A value derived from the base key defining the local context.
        seed: [u8; KEY_LOCAL_SEED_LENGTH],
        /// A hash identifying the stored data.
        hash: [u8; BLAKE2B_DIGEST_LENGTH],
    },
}

impl Key {
    /// Constructs a new [`Key::Local`] by hashing `seed` concatenated with `key_bytes`.
    pub fn local(seed: [u8; KEY_LOCAL_SEED_LENGTH], key_bytes: &[u8]) -> Self {
        let key_hash = hash(&key_bytes);
        Key::Local {
            seed,
            hash: key_hash,
        }
    }

    // This method is not intended to be used by third party crates.
    #[doc(hidden)]
    pub fn type_string(&self) -> String {
        match self {
            Key::Account(_) => String::from("Key::Account"),
            Key::Hash(_) => String::from("Key::Hash"),
            Key::URef(_) => String::from("Key::URef"),
            Key::Local { .. } => String::from("Key::Local"),
        }
    }

    /// Returns the maximum size a [`Key`] can be serialized into.
    pub const fn max_serialized_length() -> usize {
        KEY_LOCAL_SERIALIZED_LENGTH
    }

    /// If `self` is of type [`Key::URef`], returns `self` with the [`AccessRights`] stripped from
    /// the wrapped [`URef`], otherwise returns `self` unmodified.
    pub fn normalize(self) -> Key {
        match self {
            Key::URef(uref) => Key::URef(uref.remove_access_rights()),
            other => other,
        }
    }

    // TODO - remove this method as it's unused
    #[doc(hidden)]
    /// Creates an instance of `Key::Hash` from the hex-encoded string.  Returns `None` if
    /// `hex_encodede_hash` does not decode to a 32-byte array.
    pub fn parse_hash(hex_encodede_hash: &str) -> Option<Key> {
        decode_from_hex(hex_encodede_hash).map(Key::Hash)
    }

    // TODO - remove this method as it's unused
    #[doc(hidden)]
    /// Creates an instance of `Key::URef` from the hex-encoded string.  Returns `None` if
    /// `hex_encoded_uref` does not decode to a 32-byte array.
    pub fn parse_uref(hex_encoded_uref: &str, access_rights: AccessRights) -> Option<Key> {
        decode_from_hex(hex_encoded_uref).map(|uref| Key::URef(URef::new(uref, access_rights)))
    }

    // TODO - remove this method as it's unused
    #[doc(hidden)]
    /// Creates an instance of `Key::Local` from the hex-encoded strings.  Returns `None` if
    /// `hex_encoded_seed` does not decode to a 32-byte array, or if `hex_encoded_key_bytes` does
    /// does not decode from hex.
    pub fn parse_local(hex_encoded_seed: &str, hex_encoded_key_bytes: &str) -> Option<Key> {
        let decoded_seed = decode_from_hex(hex_encoded_seed)?;
        let decoded_key_bytes = base16::decode(drop_hex_prefix(hex_encoded_key_bytes)).ok()?;
        Some(Key::local(decoded_seed, &decoded_key_bytes))
    }

    /// Returns a human-readable version of `self`, with the inner bytes encoded to Base16.
    pub fn as_string(&self) -> String {
        match self {
            Key::Account(PublicKey::Ed25519(addr)) => {
                format!("account-ed25519-{}", base16::encode_lower(&addr.value()))
            }
            Key::Hash(addr) => format!("hash-{}", base16::encode_lower(addr)),
            Key::URef(uref) => uref.as_string(),
            Key::Local { hash, .. } => format!("local-{}", base16::encode_lower(hash)),
        }
    }

    /// Consumes and converts `self` to a [`ContractRef`] if `self` is of type [`Key::Hash`] or
    /// [`Key::URef`], otherwise returns `None`.
    pub fn to_contract_ref(self) -> Option<ContractRef> {
        match self {
            Key::URef(uref) => Some(ContractRef::URef(uref)),
            Key::Hash(id) => Some(ContractRef::Hash(id)),
            _ => None,
        }
    }

    /// Returns the inner bytes of `self` if `self` is of type [`Key::Account`], otherwise returns
    /// `None`.
    pub fn into_account(self) -> Option<PublicKey> {
        match self {
            Key::Account(bytes) => Some(bytes),
            _ => None,
        }
    }

    /// Returns the inner bytes of `self` if `self` is of type [`Key::Hash`], otherwise returns
    /// `None`.
    pub fn into_hash(self) -> Option<[u8; KEY_HASH_LENGTH]> {
        match self {
            Key::Hash(hash) => Some(hash),
            _ => None,
        }
    }

    /// Returns a reference to the inner [`URef`] if `self` is of type [`Key::URef`], otherwise
    /// returns `None`.
    pub fn as_uref(&self) -> Option<&URef> {
        match self {
            Key::URef(uref) => Some(uref),
            _ => None,
        }
    }

    /// Returns the inner [`URef`] if `self` is of type [`Key::URef`], otherwise returns `None`.
    pub fn into_uref(self) -> Option<URef> {
        match self {
            Key::URef(uref) => Some(uref),
            _ => None,
        }
    }

    /// Returns the inner bytes of `self` if `self` is of type [`Key::Local`], otherwise returns
    /// `None`.
    pub fn into_local(self) -> Option<[u8; KEY_LOCAL_LENGTH]> {
        match self {
            Key::Local { seed, hash } => {
                let mut result = [0; KEY_LOCAL_LENGTH];
                result[..KEY_LOCAL_SEED_LENGTH].copy_from_slice(&seed);
                result[KEY_LOCAL_SEED_LENGTH..].copy_from_slice(&hash);
                Some(result)
            }
            _ => None,
        }
    }
}

impl Display for Key {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        match self {
            Key::Account(PublicKey::Ed25519(ed25519)) => write!(f, "Key::Account({})", ed25519),
            Key::Hash(addr) => write!(f, "Key::Hash({})", HexFmt(addr)),
            Key::URef(uref) => write!(f, "Key::{}", uref), /* Display impl for URef will append */
            // URef(…).
            Key::Local { seed, hash } => write!(f, "Key::Local({}{})", HexFmt(seed), HexFmt(hash)),
        }
    }
}

impl Debug for Key {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        write!(f, "{}", self)
    }
}

/// Drops "0x" prefix from the input string and turns rest of it into a slice.
fn drop_hex_prefix(s: &str) -> &str {
    if s.starts_with("0x") {
        &s[2..]
    } else {
        &s
    }
}

/// Tries to decode `input` as a 32-byte array.  `input` may be prefixed with "0x".  Returns `None`
/// if `input` cannot be parsed as hex, or if it does not parse to exactly 32 bytes.
fn decode_from_hex(input: &str) -> Option<[u8; KEY_HASH_LENGTH]> {
    const UNDECORATED_INPUT_LEN: usize = 2 * KEY_HASH_LENGTH;

    let undecorated_input = drop_hex_prefix(input);

    if undecorated_input.len() != UNDECORATED_INPUT_LEN {
        return None;
    }

    let mut output = [0u8; KEY_HASH_LENGTH];
    let _bytes_written = base16::decode_slice(undecorated_input, &mut output).ok()?;
    debug_assert!(_bytes_written == KEY_HASH_LENGTH);
    Some(output)
}

impl From<URef> for Key {
    fn from(uref: URef) -> Key {
        Key::URef(uref)
    }
}

impl ToBytes for Key {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = bytesrepr::unchecked_allocate_buffer(self);
        match self {
            Key::Account(public_key) => {
                result.push(ACCOUNT_ID);
                result.append(&mut public_key.to_bytes()?);
            }
            Key::Hash(hash) => {
                result.push(HASH_ID);
                result.append(&mut hash.to_bytes()?);
            }
            Key::URef(uref) => {
                result.push(UREF_ID);
                result.append(&mut uref.to_bytes()?);
            }
            Key::Local { seed, hash } => {
                result.push(LOCAL_ID);
                result.append(&mut seed.to_bytes()?);
                result.append(&mut hash.to_bytes()?);
            }
        }
        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        match self {
            Key::Account(public_key) => KEY_ID_SERIALIZED_LENGTH + public_key.serialized_length(),
            Key::Hash(_) => KEY_HASH_SERIALIZED_LENGTH,
            Key::URef(uref) => KEY_ID_SERIALIZED_LENGTH + uref.serialized_length(),
            Key::Local { .. } => KEY_LOCAL_SERIALIZED_LENGTH,
        }
    }
}

impl FromBytes for Key {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            ACCOUNT_ID => {
                let (public_key, rem): (PublicKey, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Key::Account(public_key), rem))
            }
            HASH_ID => {
                let (hash, rem): ([u8; KEY_HASH_LENGTH], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Key::Hash(hash), rem))
            }
            UREF_ID => {
                let (uref, rem): (URef, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Key::URef(uref), rem))
            }
            LOCAL_ID => {
                let (seed, rest): ([u8; KEY_LOCAL_SEED_LENGTH], &[u8]) =
                    FromBytes::from_bytes(rest)?;
                let (hash, rest): ([u8; BLAKE2B_DIGEST_LENGTH], &[u8]) =
                    FromBytes::from_bytes(rest)?;
                Ok((Key::Local { seed, hash }, rest))
            }
            _ => Err(Error::Formatting),
        }
    }
}

#[cfg(test)]
mod tests {
    use proptest::{
        prelude::*,
        string::{string_regex, RegexGeneratorStrategy},
    };

    use super::*;
    use crate::{
        bytesrepr::{Error, FromBytes},
        AccessRights, URef,
    };

    fn test_readable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_readable(), is_true)
    }

    #[test]
    fn test_is_readable() {
        test_readable(AccessRights::READ, true);
        test_readable(AccessRights::READ_ADD, true);
        test_readable(AccessRights::READ_WRITE, true);
        test_readable(AccessRights::READ_ADD_WRITE, true);
        test_readable(AccessRights::ADD, false);
        test_readable(AccessRights::ADD_WRITE, false);
        test_readable(AccessRights::WRITE, false);
    }

    fn test_writable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_writeable(), is_true)
    }

    #[test]
    fn test_is_writable() {
        test_writable(AccessRights::WRITE, true);
        test_writable(AccessRights::READ_WRITE, true);
        test_writable(AccessRights::ADD_WRITE, true);
        test_writable(AccessRights::READ, false);
        test_writable(AccessRights::ADD, false);
        test_writable(AccessRights::READ_ADD, false);
        test_writable(AccessRights::READ_ADD_WRITE, true);
    }

    fn test_addable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_addable(), is_true)
    }

    #[test]
    fn test_is_addable() {
        test_addable(AccessRights::ADD, true);
        test_addable(AccessRights::READ_ADD, true);
        test_addable(AccessRights::READ_WRITE, false);
        test_addable(AccessRights::ADD_WRITE, true);
        test_addable(AccessRights::READ, false);
        test_addable(AccessRights::WRITE, false);
        test_addable(AccessRights::READ_ADD_WRITE, true);
    }

    #[test]
    fn should_display_key() {
        let expected_hash = core::iter::repeat("0").take(64).collect::<String>();
        let addr_array = [0u8; 32];
        let public_key = PublicKey::ed25519_from(addr_array);
        let account_key = Key::Account(public_key);
        assert_eq!(
            format!("{}", account_key),
            format!("Key::Account(Ed25519({}))", expected_hash)
        );
        let uref_key = Key::URef(URef::new(addr_array, AccessRights::READ));
        assert_eq!(
            format!("{}", uref_key),
            format!("Key::URef({}, READ)", expected_hash)
        );
        let hash_key = Key::Hash(addr_array);
        assert_eq!(
            format!("{}", hash_key),
            format!("Key::Hash({})", expected_hash)
        );
        let expected_hash = core::iter::repeat("0").take(128).collect::<String>();
        let local_key = Key::Local {
            seed: addr_array,
            hash: addr_array,
        };
        assert_eq!(
            format!("{}", local_key),
            format!("Key::Local({})", expected_hash)
        );
    }

    #[test]
    fn parse_local_with_arbitrary_length() {
        let short_key = base16::encode_lower(&[42u8; 32]);
        let long_key = base16::encode_lower(&vec![42u8; 255]);
        let seed = "0102010201020102010201020102010201020102010201020102010201020102";

        let local1 = Key::parse_local(seed, &short_key).expect("should parse local with short key");
        let local2 = Key::parse_local(seed, &long_key).expect("should parse local with long key");

        // verifies that the arbitrary key length doesn't get truncated
        assert_ne!(local1, local2);
    }

    /// Create a base16 string of `length` size.
    fn base16_str_arb(length: usize) -> RegexGeneratorStrategy<String> {
        string_regex(&format!("[0-9a-f]{{{}}}", length)).unwrap()
    }

    proptest! {

        #[test]
        fn should_fail_parse_small_base16_to_key(base16_addr in base16_str_arb(32)) {
            assert!(Key::parse_hash(&base16_addr).is_none());
            assert!(Key::parse_uref(&base16_addr, AccessRights::READ).is_none());
            assert!(Key::parse_local(&base16_addr, &base16_addr).is_none());
        }

        #[test]
        fn should_parse_64_base16_to_key(base16_addr in base16_str_arb(64)) {
            assert!(Key::parse_hash(&base16_addr).is_some());
            assert!(Key::parse_uref(&base16_addr, AccessRights::READ).is_some());
            assert!(Key::parse_local(&base16_addr, &base16_addr).is_some());
        }

        #[test]
        fn should_fail_parse_long_base16_to_key(base16_addr in base16_str_arb(70)) {
            assert!(Key::parse_hash(&base16_addr).is_none());
            assert!(Key::parse_uref(&base16_addr, AccessRights::READ).is_none());
            assert!(Key::parse_local(&base16_addr, &base16_addr).is_none());
        }

        #[test]
        fn should_fail_parse_not_base16_input(invalid_addr in "[f-z]{32}") {
            // Only a-f characters are valid hex.
            assert!(Key::parse_hash(&invalid_addr).is_none());
            assert!(Key::parse_uref(&invalid_addr, AccessRights::READ).is_none());
            assert!(Key::parse_local(&invalid_addr, &invalid_addr).is_none());
        }

        #[test]
        fn should_parse_base16_0x_prefixed(base16_addr in base16_str_arb(64)) {
            let preppended = format!("0x{}", &base16_addr);
            assert!(Key::parse_hash(&preppended).is_some());
            assert_eq!(Key::parse_hash(&preppended), Key::parse_hash(&base16_addr));
        }
    }

    #[test]
    fn abuse_vec_key() {
        // Prefix is 2^32-1 = shouldn't allocate that much
        let bytes: Vec<u8> = vec![255, 255, 255, 255, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
        let res: Result<(Vec<Key>, &[u8]), _> = FromBytes::from_bytes(&bytes);
        #[cfg(target_os = "linux")]
        assert_eq!(res.expect_err("should fail"), Error::OutOfMemory);
        #[cfg(target_os = "macos")]
        assert_eq!(res.expect_err("should fail"), Error::EarlyEndOfStream);
    }

    #[test]
    fn decode_from_hex() {
        let input = [255; 32]; // `255` decimal is `ff` hex
        let hex_input = "f".repeat(64);
        assert_eq!(Some(input), super::decode_from_hex(&hex_input));

        let prefixed_hex_input = format!("0x{}", hex_input);
        assert_eq!(Some(input), super::decode_from_hex(&prefixed_hex_input));

        let bad_prefix = format!("0X{}", hex_input);
        assert!(super::decode_from_hex(&bad_prefix).is_none());

        let too_short = "f".repeat(63);
        assert!(super::decode_from_hex(&too_short).is_none());

        let too_long = "f".repeat(65);
        assert!(super::decode_from_hex(&too_long).is_none());

        let invalid_hex = "g".repeat(64);
        assert!(super::decode_from_hex(&invalid_hex).is_none());
    }

    #[test]
    fn check_key_account_getters() {
        let account = [42; 32];
        let public_key = PublicKey::ed25519_from(account);
        let key1 = Key::Account(public_key);
        assert_eq!(key1.into_account(), Some(public_key));
        assert!(key1.into_hash().is_none());
        assert!(key1.as_uref().is_none());
        assert!(key1.into_local().is_none());
    }

    #[test]
    fn check_key_hash_getters() {
        let hash = [42; KEY_HASH_LENGTH];
        let key1 = Key::Hash(hash);
        assert!(key1.into_account().is_none());
        assert_eq!(key1.into_hash(), Some(hash));
        assert!(key1.as_uref().is_none());
        assert!(key1.into_local().is_none());
    }

    #[test]
    fn check_key_uref_getters() {
        let uref = URef::new([42; 32], AccessRights::READ_ADD_WRITE);
        let key1 = Key::URef(uref);
        assert!(key1.into_account().is_none());
        assert!(key1.into_hash().is_none());
        assert_eq!(key1.as_uref(), Some(&uref));
        assert!(key1.into_local().is_none());
    }

    #[test]
    fn check_key_local_getters() {
        let local = [42; KEY_LOCAL_LENGTH];
        let key1 = Key::Local {
            seed: [42; KEY_LOCAL_SEED_LENGTH],
            hash: [42; BLAKE2B_DIGEST_LENGTH],
        };
        assert!(key1.into_account().is_none());
        assert!(key1.into_hash().is_none());
        assert!(key1.as_uref().is_none());
        assert_eq!(key1.into_local().map(|x| x.to_vec()), Some(local.to_vec()));
    }

    #[test]
    fn key_max_serialized_length() {
        let key_account = Key::Account(PublicKey::ed25519_from([42; 32]));
        assert!(key_account.serialized_length() < Key::max_serialized_length());

        let key_hash = Key::Hash([42; 32]);
        assert!(key_hash.serialized_length() < Key::max_serialized_length());

        let key_uref = Key::URef(URef::new([42; 32], AccessRights::READ));
        assert!(key_uref.serialized_length() < Key::max_serialized_length());

        let key_local = Key::local([42; 32], &[42; 32]);
        assert_eq!(key_local.serialized_length(), Key::max_serialized_length());
    }
}
