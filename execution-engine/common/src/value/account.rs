use crate::bytesrepr::{Error, FromBytes, ToBytes, U32_SIZE, U64_SIZE, U8_SIZE};
use crate::key::{Key, UREF_SIZE};
use alloc::collections::btree_map::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;

pub const KEY_SIZE: usize = 32;
/// Maximum number of associated keys.
/// Value chosen arbitrary, shouldn't be too large to prevent bloating
/// `associated_keys` table.
pub const MAX_KEYS: usize = 10;

#[derive(PartialOrd, Ord, PartialEq, Eq, Clone, Debug)]
pub struct Weight(u8);

impl Weight {
    pub fn new(weight: u8) -> Weight {
        Weight(weight)
    }
}

pub const WEIGHT_SIZE: usize = U8_SIZE;

#[derive(PartialOrd, Ord, PartialEq, Eq, Clone, Debug)]
pub struct PublicKey([u8; KEY_SIZE]);

pub const PUBLIC_KEY_SIZE: usize = KEY_SIZE * U8_SIZE;

impl PublicKey {
    pub fn new(key: [u8; KEY_SIZE]) -> PublicKey {
        PublicKey(key)
    }
}

impl From<[u8; KEY_SIZE]> for PublicKey {
    fn from(key: [u8; KEY_SIZE]) -> Self {
        PublicKey(key)
    }
}

#[derive(PartialEq, Eq, Debug)]
pub enum AddKeyFailure {
    MaxKeysLimit,
    DuplicateKey,
}

#[derive(PartialOrd, Ord, PartialEq, Eq, Clone, Debug)]
pub struct AssociatedKeys(BTreeMap<PublicKey, Weight>);

impl AssociatedKeys {
    pub fn empty() -> AssociatedKeys {
        AssociatedKeys(BTreeMap::new())
    }

    pub fn new(key: PublicKey, weight: Weight) -> AssociatedKeys {
        let mut bt: BTreeMap<PublicKey, Weight> = BTreeMap::new();
        bt.insert(key, weight);
        AssociatedKeys(bt)
    }

    /// Adds new AssociatedKey to the set.
    /// Returns true if added successfully, false otherwise.
    #[allow(clippy::map_entry)]
    pub fn add_key(&mut self, key: PublicKey, weight: Weight) -> Result<(), AddKeyFailure> {
        if self.0.len() == MAX_KEYS {
            Err(AddKeyFailure::MaxKeysLimit)
        } else if self.0.contains_key(&key) {
            Err(AddKeyFailure::DuplicateKey)
        } else {
            self.0.insert(key, weight);
            Ok(())
        }
    }

    /// Removes key from the associated keys set.
    /// Returns true if value was found in the set prior to the removal, false otherwise.
    pub fn remove_key(&mut self, key: &PublicKey) -> bool {
        self.0.remove(key).is_some()
    }

    pub fn get(&self, key: &PublicKey) -> Option<&Weight> {
        self.0.get(key)
    }
}

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct Account {
    public_key: [u8; 32],
    nonce: u64,
    known_urefs: BTreeMap<String, Key>,
    associated_keys: AssociatedKeys,
}

impl Account {
    pub fn new(
        public_key: [u8; 32],
        nonce: u64,
        known_urefs: BTreeMap<String, Key>,
        associated_keys: AssociatedKeys,
    ) -> Self {
        Account {
            public_key,
            nonce,
            known_urefs,
            associated_keys,
        }
    }

    pub fn insert_urefs(&mut self, keys: &mut BTreeMap<String, Key>) {
        self.known_urefs.append(keys);
    }

    pub fn urefs_lookup(&self) -> &BTreeMap<String, Key> {
        &self.known_urefs
    }

    pub fn get_urefs_lookup(self) -> BTreeMap<String, Key> {
        self.known_urefs
    }

    pub fn pub_key(&self) -> &[u8] {
        &self.public_key
    }

    pub fn associated_keys(&self) -> &AssociatedKeys {
        &self.associated_keys
    }

    pub fn get_associated_keys(self) -> AssociatedKeys {
        self.associated_keys
    }

    pub fn nonce(&self) -> u64 {
        self.nonce
    }
}

impl ToBytes for Weight {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for Weight {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (byte, rem): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        Ok((Weight::new(byte), rem))
    }
}

impl ToBytes for PublicKey {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for PublicKey {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (key_bytes, rem): ([u8; KEY_SIZE], &[u8]) = FromBytes::from_bytes(bytes)?;
        Ok((PublicKey::new(key_bytes), rem))
    }
}

impl ToBytes for AssociatedKeys {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for AssociatedKeys {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (keys_map, rem): (BTreeMap<PublicKey, Weight>, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut keys = AssociatedKeys::empty();
        keys_map.into_iter().for_each(|(k, v)| {
            // NOTE: we're ignoring potential errors (duplicate key, maximum number of elements).
            // This is safe, for now, as we were the ones that serialized `AssociatedKeys` in the
            // first place.
            keys.add_key(k, v).unwrap();
        });
        Ok((keys, rem))
    }
}

impl ToBytes for Account {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let associated_keys_size =
            self.associated_keys.0.len() * (PUBLIC_KEY_SIZE + WEIGHT_SIZE) + U32_SIZE;
        let known_urefs_size = UREF_SIZE * self.known_urefs.len() + U32_SIZE;
        if known_urefs_size + associated_keys_size
            >= u32::max_value() as usize - KEY_SIZE - U64_SIZE
        {
            return Err(Error::OutOfMemoryError);
        }
        let mut result: Vec<u8> =
            Vec::with_capacity(KEY_SIZE + U64_SIZE + known_urefs_size + associated_keys_size);
        result.extend(&self.public_key.to_bytes()?);
        result.append(&mut self.nonce.to_bytes()?);
        result.append(&mut self.known_urefs.to_bytes()?);
        result.append(&mut self.associated_keys.to_bytes()?);
        Ok(result)
    }
}

impl FromBytes for Account {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (public_key, rem1): ([u8; 32], &[u8]) = FromBytes::from_bytes(bytes)?;
        let (nonce, rem2): (u64, &[u8]) = FromBytes::from_bytes(rem1)?;
        let (known_urefs, rem3): (BTreeMap<String, Key>, &[u8]) = FromBytes::from_bytes(rem2)?;
        let (associated_keys, rem4): (AssociatedKeys, &[u8]) = FromBytes::from_bytes(rem3)?;
        Ok((
            Account {
                public_key,
                nonce,
                known_urefs,
                associated_keys,
            },
            rem4,
        ))
    }
}

#[cfg(test)]
mod tests {
    use crate::value::account::{
        AddKeyFailure, AssociatedKeys, PublicKey, Weight, KEY_SIZE, MAX_KEYS,
    };

    #[test]
    fn associated_keys_add() {
        let mut keys = AssociatedKeys::new([0u8; KEY_SIZE].into(), Weight::new(1));
        let new_pk = PublicKey([1u8; KEY_SIZE]);
        let new_pk_weight = Weight::new(2);
        assert!(keys.add_key(new_pk.clone(), new_pk_weight.clone()).is_ok());
        assert_eq!(keys.get(&new_pk), Some(&new_pk_weight))
    }

    #[test]
    fn associated_keys_add_full() {
        let map = (0..MAX_KEYS).map(|k| (PublicKey([k as u8; KEY_SIZE]), Weight::new(k as u8)));
        assert_eq!(map.len(), 10);
        let mut keys = {
            let mut tmp = AssociatedKeys::empty();
            map.for_each(|(key, weight)| assert!(tmp.add_key(key, weight).is_ok()));
            tmp
        };
        assert_eq!(
            keys.add_key(PublicKey([100u8; KEY_SIZE]), Weight::new(100)),
            Err(AddKeyFailure::MaxKeysLimit)
        )
    }

    #[test]
    fn associated_keys_add_duplicate() {
        let pk = PublicKey([0u8; KEY_SIZE]);
        let weight = Weight::new(1);
        let mut keys = AssociatedKeys::new(pk.clone(), weight.clone());
        assert_eq!(
            keys.add_key(pk.clone(), Weight::new(10)),
            Err(AddKeyFailure::DuplicateKey)
        );
        assert_eq!(keys.get(&pk), Some(&weight));
    }

    #[test]
    fn associated_keys_remove() {
        let pk = PublicKey([0u8; KEY_SIZE]);
        let weight = Weight::new(1);
        let mut keys = AssociatedKeys::new(pk.clone(), weight.clone());
        assert!(keys.remove_key(&pk));
        assert!(!keys.remove_key(&PublicKey([1u8; KEY_SIZE])));
    }
}
