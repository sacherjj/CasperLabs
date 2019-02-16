use crate::transform::Transform;
use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;
use common::bytesrepr::*;
use common::key::Key;
use common::value::Value;
use error::Error;
use gs::*;
use history::*;
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

pub struct InMemGS(Arc<BTreeMap<Key, Value>>);

/// In memory representation of the versioned global state
/// store - stores a snapshot of the global state at the specific block
/// history - stores all the snapshots of the global state
pub struct InMemHist {
    history: HashMap<[u8; 32], Arc<BTreeMap<Key, Value>>>,
}

impl InMemHist {
    pub fn new(empty_root_hash: &[u8; 32]) -> InMemHist {
        InMemHist::new_initialized(empty_root_hash, BTreeMap::new())
    }

    pub fn new_initialized(
        empty_root_hash: &[u8; 32],
        init_state: BTreeMap<Key, Value>,
    ) -> InMemHist {
        let mut hist: HashMap<[u8; 32], Arc<BTreeMap<Key, Value>>> = HashMap::new();
        hist.insert(empty_root_hash.clone(), Arc::new(init_state));
        InMemHist { history: hist }
    }

    //TODO(mateusz.gorski): I know this is not efficient and we should be caching these values
    //but for the time being it should be enough.
    fn get_root_hash(state: &BTreeMap<Key, Value>) -> [u8; 32] {
        let mut data: Vec<u8> = Vec::new();
        for (k, v) in state.iter() {
            data.extend(k.to_bytes());
            data.extend(v.to_bytes());
        }
        let mut hasher = VarBlake2b::new(32).unwrap();
        hasher.input(data);
        let mut hash_bytes = [0; 32];
        hasher.variable_result(|hash| hash_bytes.clone_from_slice(hash));
        hash_bytes
    }
}

impl DbReader for InMemGS {
    fn get(&self, k: &Key) -> Result<Value, Error> {
        match self.0.get(k) {
            None => Err(Error::KeyNotFound { key: *k }),
            Some(v) => Ok(v.clone()),
        }
    }
}

impl History<InMemGS> for InMemHist {
    fn checkout(&self, prestate_hash: [u8; 32]) -> Result<TrackingCopy<InMemGS>, Error> {
        match self.history.get(&prestate_hash) {
            None => Err(Error::RootNotFound(prestate_hash)),

            Some(arc) => {
                let gs = InMemGS(Arc::clone(arc));
                Ok(TrackingCopy::new(gs))
            }
        }
    }

    fn commit(
        &mut self,
        prestate_hash: [u8; 32],
        effects: HashMap<Key, Transform>,
    ) -> Result<[u8; 32], Error> {
        let mut base = {
            let arc = self
                .history
                .get(&prestate_hash)
                .ok_or(Error::RootNotFound(prestate_hash))?;

            Ok::<BTreeMap<Key, Value>, Error>(BTreeMap::clone(&arc))
        }?;

        effects
            .into_iter()
            .try_fold((), |_, (k, t)| {
                let maybe_curr = base.remove(&k);
                match maybe_curr {
                    None => match t {
                        Transform::Write(v) => {
                            let _ = base.insert(k, v);
                            Ok(())
                        }
                        _ => Err(Error::KeyNotFound { key: k }),
                    },
                    Some(curr) => {
                        let new_value = t.apply(curr)?;
                        let _ = base.insert(k, new_value);
                        Ok(())
                    }
                }
            })
            .and_then(|_| {
                let hash = InMemHist::get_root_hash(&base);
                self.history.insert(hash, Arc::new(base));
                Ok(hash)
            })
    }
}

#[cfg(test)]
mod tests {
    use error::*;
    use gs::inmem::*;
    use std::sync::Arc;
    use transform::Transform;

    const KEY1: Key = Key::Account([1u8; 20]);
    const KEY2: Key = Key::Account([2u8; 20]);
    const VALUE1: Value = Value::Int32(1);
    const VALUE2: Value = Value::Int32(2);
    const EMPTY_ROOT: [u8; 32] = [0u8; 32];

    fn prepopulated_hist() -> InMemHist {
        let mut map = BTreeMap::new();
        map.insert(KEY1, VALUE1.clone());
        map.insert(KEY2, VALUE2.clone());
        let mut history = HashMap::new();
        history.insert(EMPTY_ROOT, Arc::new(map));
        InMemHist { history: history }
    }

    fn checkout<R: DbReader, H: History<R>>(hist: &H, hash: [u8; 32]) -> TrackingCopy<R> {
        let res = hist.checkout(hash);
        assert!(res.is_ok());
        res.unwrap()
    }

    fn commit<R: DbReader, H: History<R>>(
        hist: &mut H,
        hash: [u8; 32],
        effects: HashMap<Key, Transform>,
    ) -> [u8; 32] {
        let res = hist.commit(hash, effects);
        assert!(res.is_ok());
        res.unwrap()
    }

    #[test]
    fn test_inmem_checkout() {
        // Tests out to empty root hash and validates that
        // its content is as expeced.
        let hist = prepopulated_hist();
        let res = hist.checkout(EMPTY_ROOT);
        assert!(res.is_ok());
        let mut tc = res.unwrap();
        assert_eq!(tc.get(&KEY1).unwrap(), VALUE1);
        assert_eq!(tc.get(&KEY2).unwrap(), VALUE2);
    }

    #[test]
    fn test_checkout_missing_hash() {
        // Tests that an error is returned when trying to checkout
        // to missing hash.
        let hist = prepopulated_hist();
        let missing_root = [1u8; 32];
        let res = hist.checkout(missing_root);
        assert!(res.is_err());
        assert_eq!(res.err(), Some(Error::RootNotFound(missing_root)));
    }

    #[test]
    fn test_checkout_commit() {
        // Tests that when changes are commited then new hash is returned
        // and values that are living under new hash are as expected.
        let mut hist = prepopulated_hist();
        let mut tc = checkout(&hist, EMPTY_ROOT);
        let add_res = tc.add(KEY1, Value::Int32(1));
        assert!(add_res.is_ok());
        let new_v2 = Value::String("I am String now!".to_owned());
        let write_res = tc.write(KEY2, new_v2.clone());
        assert!(write_res.is_ok());
        let effects = tc.effect();
        // commit changes from the tracking copy
        let hash_res = commit(&mut hist, EMPTY_ROOT, effects.1);
        // checkout to the new hash
        let mut tc_2 = checkout(&hist, hash_res);
        assert_eq!(tc_2.get(&KEY1).unwrap(), Value::Int32(2));
        assert_eq!(tc_2.get(&KEY2).unwrap(), new_v2);
    }

    #[test]
    fn test_checkout_commit_checkout() {
        // First checkout to empty root hash,
        // then it commits new transformations yielding new hash,
        // and then checks out back to the empty root hash
        // and validates that it doesn't contain commited changes
        let mut gs = prepopulated_hist();
        let mut tc = checkout(&gs, EMPTY_ROOT);
        let add_res = tc.add(KEY1, Value::Int32(1));
        assert!(add_res.is_ok());
        let new_v2 = Value::String("I am String now!".to_owned());
        let write_res = tc.write(KEY2, new_v2.clone());
        assert!(write_res.is_ok());
        let effects = tc.effect();
        // commit changes from the tracking copy
        let _ = commit(&mut gs, EMPTY_ROOT, effects.1);
        // checkout to the empty root hash
        let mut tc_2 = checkout(&gs, EMPTY_ROOT);
        assert_eq!(tc_2.get(&KEY1).unwrap(), VALUE1);
        assert_eq!(tc_2.get(&KEY2).unwrap(), VALUE2);
    }
}
