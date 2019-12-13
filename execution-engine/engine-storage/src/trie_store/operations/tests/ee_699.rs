use proptest::{arbitrary, collection, prop_oneof, strategy::Strategy};

use contract_ffi::{
    bytesrepr::{self, FromBytes, ToBytes},
    gens,
    key::LOCAL_SEED_SIZE,
    uref::URef,
};
use engine_shared::{make_array_newtype, newtypes::Blake2bHash};

pub const BASIC_SIZE: usize = 4;
pub const SIMILAR_SIZE: usize = 4;
pub const FANCY_SIZE: usize = 5;
pub const LONG_SIZE: usize = 8;

const PUBLIC_KEY_BASIC_ID: u8 = 0;
const PUBLIC_KEY_SIMILAR_ID: u8 = 1;
const PUBLIC_KEY_FANCY_ID: u8 = 2;
const PUBLIC_KEY_LONG_ID: u8 = 3;

pub const KEY_HASH_SIZE: usize = 32;
pub const KEY_LOCAL_SIZE: usize = 32;

const KEY_ACCOUNT_ID: u8 = 0;
const KEY_HASH_ID: u8 = 1;
const KEY_UREF_ID: u8 = 2;
const KEY_LOCAL_ID: u8 = 3;

make_array_newtype!(Basic, u8, BASIC_SIZE);
make_array_newtype!(Similar, u8, SIMILAR_SIZE);
make_array_newtype!(Fancy, u8, FANCY_SIZE);
make_array_newtype!(Long, u8, LONG_SIZE);

macro_rules! impl_distribution_for_array_newtype {
    ($name:ident, $ty:ty, $len:expr) => {
        impl rand::distributions::Distribution<$name> for rand::distributions::Standard {
            fn sample<R: rand::Rng + ?Sized>(&self, rng: &mut R) -> $name {
                let mut dat = [0u8; $len];
                rng.fill_bytes(dat.as_mut());
                $name(dat)
            }
        }
    };
}

impl_distribution_for_array_newtype!(Basic, u8, BASIC_SIZE);
impl_distribution_for_array_newtype!(Similar, u8, SIMILAR_SIZE);
impl_distribution_for_array_newtype!(Fancy, u8, FANCY_SIZE);
impl_distribution_for_array_newtype!(Long, u8, LONG_SIZE);

macro_rules! make_array_newtype_arb {
    ($name:ident, $ty:ty, $len:expr, $fn_name:ident) => {
        fn $fn_name() -> impl Strategy<Value = $name> {
            collection::vec(arbitrary::any::<$ty>(), $len).prop_map(|values| {
                let mut dat = [0u8; $len];
                dat.copy_from_slice(values.as_slice());
                $name(dat)
            })
        }
    };
}

make_array_newtype_arb!(Basic, u8, BASIC_SIZE, basic_arb);
make_array_newtype_arb!(Similar, u8, SIMILAR_SIZE, similar_arb);
make_array_newtype_arb!(Fancy, u8, FANCY_SIZE, fancy_arb);
make_array_newtype_arb!(Long, u8, LONG_SIZE, long_arb);

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum PublicKey {
    Basic(Basic),
    Similar(Similar),
    Fancy(Fancy),
    Long(Long),
}

impl ToBytes for PublicKey {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        // TODO: use Vec::with_capacity
        let mut ret = Vec::new();
        match self {
            PublicKey::Basic(key) => {
                ret.push(PUBLIC_KEY_BASIC_ID);
                ret.extend(key.to_bytes()?)
            }
            PublicKey::Similar(key) => {
                ret.push(PUBLIC_KEY_SIMILAR_ID);
                ret.extend(key.to_bytes()?)
            }
            PublicKey::Fancy(key) => {
                ret.push(PUBLIC_KEY_FANCY_ID);
                ret.extend(key.to_bytes()?)
            }
            PublicKey::Long(key) => {
                ret.push(PUBLIC_KEY_LONG_ID);
                ret.extend(key.to_bytes()?)
            }
        };
        Ok(ret)
    }
}

impl FromBytes for PublicKey {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (id, rem): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            PUBLIC_KEY_BASIC_ID => {
                let (key, rem): (Basic, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((PublicKey::Basic(key), rem))
            }
            PUBLIC_KEY_SIMILAR_ID => {
                let (key, rem): (Similar, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((PublicKey::Similar(key), rem))
            }
            PUBLIC_KEY_FANCY_ID => {
                let (key, rem): (Fancy, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((PublicKey::Fancy(key), rem))
            }
            PUBLIC_KEY_LONG_ID => {
                let (key, rem): (Long, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((PublicKey::Long(key), rem))
            }
            _ => Err(bytesrepr::Error::FormattingError),
        }
    }
}

fn public_key_arb() -> impl Strategy<Value = PublicKey> {
    prop_oneof![
        basic_arb().prop_map(PublicKey::Basic),
        similar_arb().prop_map(PublicKey::Similar),
        fancy_arb().prop_map(PublicKey::Fancy),
        long_arb().prop_map(PublicKey::Long)
    ]
}

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum Key {
    Account(PublicKey),
    Hash([u8; KEY_HASH_SIZE]),
    URef(URef),
    Local([u8; KEY_LOCAL_SIZE]),
}

impl Key {
    pub fn local(seed: [u8; LOCAL_SEED_SIZE], key_bytes: &[u8]) -> Self {
        let bytes_to_hash: Vec<u8> = seed.iter().chain(key_bytes.iter()).copied().collect();
        let hash: [u8; KEY_LOCAL_SIZE] = Blake2bHash::new(&bytes_to_hash).into();
        Key::Local(hash)
    }
}

impl ToBytes for Key {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        // TODO: use Vec::with_capacity
        let mut ret = Vec::new();
        match self {
            Key::Account(public_key) => {
                ret.push(KEY_ACCOUNT_ID);
                ret.extend(&public_key.to_bytes()?)
            }
            Key::Hash(hash) => {
                ret.push(KEY_HASH_ID);
                ret.extend(&hash.to_bytes()?)
            }
            Key::URef(uref) => {
                ret.push(KEY_UREF_ID);
                ret.extend(&uref.to_bytes()?)
            }
            Key::Local(local) => {
                ret.push(KEY_LOCAL_ID);
                ret.extend(&local.to_bytes()?)
            }
        }
        Ok(ret)
    }
}

impl FromBytes for Key {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (id, rem): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            KEY_ACCOUNT_ID => {
                let (public_key, rem): (PublicKey, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Key::Account(public_key), rem))
            }
            KEY_HASH_ID => {
                let (hash, rem): ([u8; KEY_HASH_SIZE], &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Key::Hash(hash), rem))
            }
            KEY_UREF_ID => {
                let (uref, rem): (URef, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Key::URef(uref), rem))
            }
            KEY_LOCAL_ID => {
                let (local, rem): ([u8; KEY_LOCAL_SIZE], &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Key::Local(local), rem))
            }
            _ => Err(bytesrepr::Error::FormattingError),
        }
    }
}

fn key_arb() -> impl Strategy<Value = Key> {
    prop_oneof![
        public_key_arb().prop_map(Key::Account),
        gens::u8_slice_32().prop_map(Key::Hash),
        gens::uref_arb().prop_map(Key::URef),
        (gens::u8_slice_32(), gens::u8_slice_32()).prop_map(|(seed, key)| Key::local(seed, &key))
    ]
}

#[allow(clippy::unnecessary_operation)]
mod basics {
    use proptest::proptest;

    use super::*;

    #[test]
    fn random_key_generation_works_as_expected() {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        let a: Basic = rng.gen();
        let b: Basic = rng.gen();
        assert_ne!(a, b)
    }

    proptest! {
        #[test]
        fn key_should_roundtrip(key in key_arb()) {
            bytesrepr::test_serialization_roundtrip(&key)
        }
    }
}
