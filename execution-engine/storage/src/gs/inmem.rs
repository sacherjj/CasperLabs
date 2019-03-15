use crate::transform::Transform;
use common::bytesrepr::*;
use common::key::Key;
use common::value::Value;
use error::GlobalStateError;
use gs::*;
use history::*;
use shared::newtypes::Blake2bHash;
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

pub struct InMemGS<K, V>(Arc<BTreeMap<K, V>>);
impl<K, V> InMemGS<K, V> {
    pub fn new(map: BTreeMap<K, V>) -> Self {
        InMemGS(Arc::new(map))
    }
}

impl<K, V> Clone for InMemGS<K, V> {
    fn clone(&self) -> Self {
        InMemGS(Arc::clone(&self.0))
    }
}

impl DbReader for InMemGS<Key, Value> {
    fn get(&self, k: &Key) -> Result<Option<Value>, GlobalStateError> {
        Ok(self.0.get(k).map(Clone::clone))
    }
}

/// In memory representation of the versioned global state
/// store - stores a snapshot of the global state at the specific block
/// history - stores all the snapshots of the global state
pub struct InMemHist<K, V> {
    history: HashMap<Blake2bHash, InMemGS<K, V>>,
}

impl<K: Ord, V> InMemHist<K, V> {
    pub fn new(empty_root_hash: &Blake2bHash) -> InMemHist<K, V> {
        InMemHist::new_initialized(empty_root_hash, BTreeMap::new())
    }

    pub fn new_initialized(
        empty_root_hash: &Blake2bHash,
        init_state: BTreeMap<K, V>,
    ) -> InMemHist<K, V> {
        let mut history = HashMap::new();
        history.insert(empty_root_hash.clone(), InMemGS(Arc::new(init_state)));
        InMemHist { history }
    }

    // TODO(mateusz.gorski): I know this is not efficient and we should be caching these values
    // but for the time being it should be enough.
    fn get_root_hash(state: &BTreeMap<K, V>) -> Blake2bHash
    where
        K: ToBytes,
        V: ToBytes,
    {
        let mut data: Vec<u8> = Vec::new();
        for (k, v) in state.iter() {
            data.extend(k.to_bytes());
            data.extend(v.to_bytes());
        }
        Blake2bHash::new(&data)
    }
}

impl History for InMemHist<Key, Value> {
    type Error = GlobalStateError;
    type Reader = InMemGS<Key, Value>;

    fn checkout(
        &self,
        prestate_hash: Blake2bHash,
    ) -> Result<Option<TrackingCopy<Self::Reader>>, Self::Error> {
        match self.history.get(&prestate_hash) {
            None => Ok(None),
            Some(gs) => Ok(Some(TrackingCopy::new(gs.clone()))),
        }
    }

    fn commit(
        &mut self,
        prestate_hash: Blake2bHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, Self::Error> {
        let base_result = self
            .history
            .get(&prestate_hash)
            .map(|gs| BTreeMap::clone(&gs.0));
        match base_result {
            Some(mut base) => {
                for (k, t) in effects.into_iter() {
                    let maybe_curr = base.remove(&k);
                    match maybe_curr {
                        None => match t {
                            Transform::Write(v) => {
                                base.insert(k, v);
                            }
                            _ => return Ok(CommitResult::KeyNotFound(k)),
                        },
                        Some(curr) => {
                            let new_value = t.apply(curr)?;
                            base.insert(k, new_value);
                        }
                    }
                }
                let hash = InMemHist::get_root_hash(&base);
                self.history.insert(hash, InMemGS(Arc::new(base)));
                Ok(CommitResult::Success(hash))
            }
            None => Ok(CommitResult::RootNotFound),
        }
    }
}

#[cfg(test)]
mod tests {
    use gs::inmem::*;
    use std::sync::Arc;
    use transform::Transform;

    const KEY1: Key = Key::Account([1u8; 20]);
    const KEY2: Key = Key::Account([2u8; 20]);
    const VALUE1: Value = Value::Int32(1);
    const VALUE2: Value = Value::Int32(2);

    fn prepopulated_hist() -> InMemHist<Key, Value> {
        let empty_root_hash = [0u8; 32].into();
        let mut map = BTreeMap::new();
        map.insert(KEY1, VALUE1.clone());
        map.insert(KEY2, VALUE2.clone());
        let mut history = HashMap::new();
        history.insert(empty_root_hash, InMemGS(Arc::new(map)));
        InMemHist { history }
    }

    fn checkout<H>(hist: &H, hash: Blake2bHash) -> TrackingCopy<H::Reader>
    where
        H: History,
        H::Error: std::fmt::Debug,
    {
        let res = hist.checkout(hash);
        assert!(res.is_ok());
        // The res is of type Result<Option<_>, _>> so we have to unwrap twice.
        // This is fine to do in the test since the point of this method is to provide
        // helper for the original method.
        res.unwrap().unwrap()
    }

    fn commit<H>(hist: &mut H, hash: Blake2bHash, effects: HashMap<Key, Transform>) -> Blake2bHash
    where
        H: History,
        H::Error: std::fmt::Debug,
    {
        let res = hist.commit(hash, effects);
        // The res is of type Result<Option<_>, _>> so we have to unwrap twice.
        // This is fine to do in the test since the point of this method is to provide
        // helper for the original method.
        if let CommitResult::Success(new_hash) = res.unwrap() {
            new_hash
        } else {
            panic!("Test commit failed.")
        }
    }

    #[test]
    fn test_inmem_checkout() {
        // Tests out to empty root hash and validates that
        // its content is as expeced.
        let empty_root_hash = [0u8; 32].into();
        let hist = prepopulated_hist();
        let mut tc = checkout(&hist, empty_root_hash);
        assert_eq!(tc.get(&KEY1).unwrap().unwrap(), VALUE1);
        assert_eq!(tc.get(&KEY2).unwrap().unwrap(), VALUE2);
    }

    #[test]
    fn test_checkout_missing_hash() {
        // Tests that an error is returned when trying to checkout
        // to missing hash.
        let hist = prepopulated_hist();
        let missing_root: Blake2bHash = [1u8; 32].into();
        let res = hist.checkout(missing_root);
        assert!(res.unwrap().is_none());
    }

    #[test]
    fn test_checkout_commit() {
        // Tests that when changes are commited then new hash is returned
        // and values that are living under new hash are as expected.
        let empty_root_hash = [0u8; 32].into();
        let mut hist = prepopulated_hist();
        let mut tc = checkout(&hist, empty_root_hash);
        let add_res = tc.add(KEY1, Value::Int32(1));
        assert!(add_res.is_ok());
        let new_v2 = Value::String("I am String now!".to_owned());
        let write_res = tc.write(KEY2, new_v2.clone());
        assert!(write_res.is_ok());
        let effects = tc.effect();
        // commit changes from the tracking copy
        let hash_res = commit(&mut hist, empty_root_hash, effects.1);
        // checkout to the new hash
        let mut tc_2 = checkout(&hist, hash_res);
        assert_eq!(tc_2.get(&KEY1).unwrap().unwrap(), Value::Int32(2));
        assert_eq!(tc_2.get(&KEY2).unwrap().unwrap(), new_v2);
    }

    #[test]
    fn test_checkout_commit_checkout() {
        // First checkout to empty root hash,
        // then it commits new transformations yielding new hash,
        // and then checks out back to the empty root hash
        // and validates that it doesn't contain commited changes
        let empty_root_hash = [0u8; 32].into();
        let mut gs = prepopulated_hist();
        let mut tc = checkout(&gs, empty_root_hash);
        let add_res = tc.add(KEY1, Value::Int32(1));
        assert!(add_res.is_ok());
        let new_v2 = Value::String("I am String now!".to_owned());
        let write_res = tc.write(KEY2, new_v2.clone());
        assert!(write_res.is_ok());
        let key3 = Key::Account([3u8; 20]);
        let value3 = Value::Int32(3);
        let write_res = tc.write(key3, value3.clone());
        assert!(write_res.is_ok());
        assert_eq!(tc.get(&key3).unwrap().unwrap(), value3);
        let effects = tc.effect();
        // commit changes from the tracking copy
        let _ = commit(&mut gs, empty_root_hash, effects.1);
        // checkout to the empty root hash
        let mut tc_2 = checkout(&gs, empty_root_hash);
        assert_eq!(tc_2.get(&KEY1).unwrap().unwrap(), VALUE1);
        assert_eq!(tc_2.get(&KEY2).unwrap().unwrap(), VALUE2);
        // test that value inserted later are not visible in the past commits.
        assert_eq!(tc_2.get(&key3), Ok(None));
    }
}
