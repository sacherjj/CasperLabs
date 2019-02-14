use crate::transform::Transform;
use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;
use common::bytesrepr::*;
use common::key::Key;
use common::value::Value;
use error::Error;
use gs::*;
use history::*;
use parking_lot::Mutex;
use std::collections::HashMap;
use std::sync::Arc;

/// In memory representation of the versioned global state
/// store - stores a snapshot of the global state at the specific block
/// history - stores all the snapshots of the global state
pub struct InMemGS {
    active_state: Mutex<HashMap<Key, Value>>,
    history: Mutex<HashMap<[u8; 32], HashMap<Key, Value>>>,
}

impl InMemGS {
    pub fn new() -> InMemGS {
        InMemGS {
            active_state: Mutex::new(HashMap::new()),
            history: Mutex::new(HashMap::new()),
        }
    }
}

impl DbReader for InMemGS {
    fn get(&self, k: &Key) -> Result<Value, Error> {
        match self.active_state.lock().get(k) {
            None => Err(Error::KeyNotFound { key: *k }),
            Some(v) => Ok(v.clone()),
        }
    }
}

impl History<Self> for InMemGS {
    /// **WARNING**
    /// This will drop any changes made to `active_store` and replace it with
    /// the state under passed hash.
    fn checkout(&self, prestate_hash: [u8; 32]) -> Result<TrackingCopy<InMemGS>, Error> {
        if (!self.history.lock().contains_key(&prestate_hash)) {
            Err(Error::RootNotFound(prestate_hash))
        } else {
            let mut store = self.active_state.lock();
            *store = self.history.lock().get(&prestate_hash).unwrap().clone();
            Ok(TrackingCopy::new(self))
        }
    }

    fn commit(&self, effects: HashMap<Key, Transform>) -> Result<[u8; 32], Error> {
        effects
            .into_iter()
            .try_fold((), |_, (k, t)| {
                let maybe_curr = self.active_state.lock().remove(&k);
                match maybe_curr {
                    None => match t {
                        Transform::Write(v) => {
                            let _ = self.active_state.lock().insert(k, v);
                            Ok(())
                        }
                        _ => Err(Error::KeyNotFound { key: k }),
                    },
                    Some(curr) => {
                        let new_value = t.apply(curr)?;
                        let _ = self.active_state.lock().insert(k, new_value);
                        Ok(())
                    }
                }
            })
            .and_then(|_| {
                //TODO(mateusz.gorski): Awful waste of time and space
                let active_store = self.active_state.lock().clone();
                let hash = self.get_root_hash()?;
                self.history.lock().insert(hash, active_store);
                Ok(hash)
            })
    }

    //TODO(mateusz.gorski): I know this is not efficient and we should be caching these values
    //but for the time being it should be enough.
    //TODO(mateusz.gorski): It doesn't seem to me like this method can ever return en Error?
    fn get_root_hash(&self) -> Result<[u8; 32], Error> {
        let mut data: Vec<u8> = Vec::new();
        for (k, v) in self.active_state.lock().iter() {
            data.extend(k.to_bytes());
            data.extend(v.to_bytes());
        }
        let mut hasher = VarBlake2b::new(32).unwrap();
        hasher.input(data);
        let mut hash_bytes = [0; 32];
        hasher.variable_result(|hash| hash_bytes.clone_from_slice(hash));
        Ok(hash_bytes)
    }
}

#[cfg(test)]
mod tests {
    use parking_lot::Mutex;
    use std::sync::Arc;
    use history::*;
    use op::Op;
    use gs::*;
    use gs::inmem::InMemGS;
    use error::*;
    use transform::Transform;

    const KEY1: Key = Key::Account([1u8; 20]);
    const KEY2: Key = Key::Account([2u8; 20]);
    const VALUE1: Value = Value::Int32(1);
    const VALUE2: Value = Value::Int32(2);
    const EMPTY_ROOT: [u8;32] = [0u8;32];

    fn prepopulated_gs() -> InMemGS {
        let mut map = HashMap::new();
        map.insert(KEY1, VALUE1.clone());
        map.insert(KEY2, VALUE2.clone());
        let mut history = HashMap::new();
        history.insert(EMPTY_ROOT, map);
        InMemGS {
            active_state: Mutex::new(HashMap::new()),
            history: Mutex::new(history),
        }
    }

    fn checkout<R: DbReader, H: History<R>>(gs: &H, hash: [u8;32]) -> TrackingCopy<R> {
        let res = gs.checkout(hash);
        assert!(res.is_ok());
        res.unwrap()
    }

    fn commit<R: DbReader, H: History<R>>(gs: &H, effects: HashMap<Key, Transform>) -> [u8;32] {
        let res = gs.commit(effects);
        assert!(res.is_ok());
        res.unwrap()
    }


    /// Returns new hash of the tree that is the result of
    /// checking out to the supplied hash and applying the effects.
    fn checkout_commit<R: DbReader, H: History<R>>(h: &H, hash: [u8;32], effects: HashMap<Key, (Op, Value)>) -> [u8;32] {
        let mut tc = checkout(h, hash);
        for (k, (op, v)) in effects.into_iter() {
            match op {
                Op::Write => tc.write(k, v).expect("Write to TrackingCopy in test should be a success."),
                Op::Add => tc.add(k, v).expect("Add to TrackingCopy in test should be a success."),
                _ => {},
            }
        };
        let effects = tc.effect();
        commit(h, effects.1)
    }

    #[test]
    fn test_inmem_checkout() {
        // Tests out to empty root hash and validates that
        // its content is as expeced.
        let gs = prepopulated_gs();
        let res = gs.checkout(EMPTY_ROOT);
        assert!(res.is_ok());
        let mut tc = res.unwrap();
        assert_eq!(tc.get(&KEY1).unwrap(), VALUE1);
        assert_eq!(tc.get(&KEY2).unwrap(), VALUE2);
    }

    #[test]
    fn test_checkout_missing_hash() {
        // Tests that an error is returned when trying to checkout
        // to missing hash.
        let gs = prepopulated_gs();
        let missing_root = [1u8;32];
        let res = gs.checkout(missing_root);
        assert!(res.is_err());
        assert_eq!(res.err(), Some(Error::RootNotFound(missing_root)));
    }

    #[test]
    fn test_checkout_commit() {
        // Tests that when changes are commited then new hash is returned
        // and values that are living under new hash are as expected.
        let gs = prepopulated_gs();
        let mut tc = checkout(&gs, EMPTY_ROOT);
        tc.add(KEY1, Value::Int32(1));
        let new_v2 = Value::String("I am String now!".to_owned());
        tc.write(KEY2, new_v2.clone());
        let effects = tc.effect();
        // commit changes from the tracking copy
        let hash_res = commit(&gs, effects.1);
        // checkout to the new hash
        let mut tc_2 = checkout(&gs, hash_res);
        assert_eq!(tc_2.get(&KEY1).unwrap(), Value::Int32(2));
        assert_eq!(tc_2.get(&KEY2).unwrap(), new_v2);
    }

    #[test]
    fn test_checkout_commit_checkout() {
        // First checkout to empty root hash,
        // then it commits new transformations yielding new hash,
        // and then checks out back to the empty root hash
        // and validates that it doesn't contain commited changes
        let gs = prepopulated_gs();
        let mut tc = checkout(&gs, EMPTY_ROOT); 
        tc.add(KEY1, Value::Int32(1));
        let new_v2 = Value::String("I am String now!".to_owned());
        tc.write(KEY2, new_v2.clone());
        let effects = tc.effect();
        // commit changes from the tracking copy
        let hash_res = commit(&gs, effects.1);
        // checkout to the empty root hash
        let mut tc_2 = checkout(&gs, EMPTY_ROOT);
        assert_eq!(tc_2.get(&KEY1).unwrap(), VALUE1);
        assert_eq!(tc_2.get(&KEY2).unwrap(), VALUE2);
    }
}
