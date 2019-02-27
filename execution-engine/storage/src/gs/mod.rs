use super::error::Error;
use super::op::Op;
use super::transform::Transform;
use crate::common::key::Key;
use crate::common::value::{Account, Value};
use std::collections::{BTreeMap, HashMap};

pub mod inmem;
pub mod lmdb;
pub mod trackingcopy;

pub use self::trackingcopy::TrackingCopy;

#[derive(Debug)]
pub struct ExecutionEffect(pub HashMap<Key, Op>, pub HashMap<Key, Transform>);

pub trait DbReader {
    fn get(&self, k: &Key) -> Result<Value, Error>;
}

pub fn mocked_account(account_addr: [u8; 20]) -> BTreeMap<Key, Value> {
    let account = Account::new([48u8; 32], 0, BTreeMap::new());
    let mut map = BTreeMap::new();
    map.insert(Key::Account(account_addr), Value::Acct(account));

    map
}

#[derive(Debug)]
pub enum QueryResult {
    Success(Value),
    ValueNotFound(String),
}

pub fn query_tc<R: DbReader>(
    tc: &mut TrackingCopy<R>,
    base_key: Key,
    path: &[String],
) -> Result<QueryResult, Error> {
    let base_value = tc.read(base_key)?;

    let result = path.iter().try_fold(
        base_value,
        //We encode the two possible short-circuit conditions with
        //Option<Error>, where the None case corresponds to
        //QueryResult::ValueNotFound and Some(_) corresponds to
        //a storage-related error.
        |curr_value, name| -> Result<Value, Option<Error>> {
            match curr_value {
                Value::Acct(account) => {
                    if let Some(key) = account.urefs_lookup().get(name) {
                        tc.read(*key).map_err(|e| Some(e.into()))
                    } else {
                        Err(None)
                    }
                }

                Value::Contract { known_urefs, .. } => {
                    if let Some(key) = known_urefs.get(name) {
                        tc.read(*key).map_err(|e| Some(e.into()))
                    } else {
                        Err(None)
                    }
                }

                _ => Err(None),
            }
        },
    );

    match result {
        Ok(value) => Ok(QueryResult::Success(value)),

        Err(None) => {
            let mut full_path = format!("{:?}", base_key);
            for p in path {
                full_path.push_str("/");
                full_path.push_str(p);
            }
            Ok(QueryResult::ValueNotFound(full_path))
        }

        Err(Some(err)) => Err(err),
    }
}

#[cfg(test)]
mod tests {
    use common::key::Key;
    use common::value::{Account, Value};
    use error::Error;
    use gens::gens::*;
    use gs::inmem::InMemGS;
    use gs::{query_tc, QueryResult, TrackingCopy};
    use proptest::collection::vec;
    use proptest::prelude::*;
    use std::collections::BTreeMap;

    proptest! {
      #[test]
      fn query_empty_path(k in key_arb(), missing_key in key_arb(), v in value_arb()) {
          let mut map = BTreeMap::new();
          map.insert(k, v.clone());
          let gs = InMemGS::new(map);
          let mut tc = TrackingCopy::new(gs);
          let empty_path = Vec::new();
          if let Ok(QueryResult::Success(result)) = query_tc(&mut tc, k, &empty_path) {
            assert_eq!(v, result);
          } else {
            panic!("Query failed when it should not have!");
          }

          if missing_key != k {
            let result = query_tc(&mut tc, missing_key, &empty_path);
            assert_matches!(result, Err(Error::KeyNotFound(_)));
          }
      }

      #[test]
      fn query_contract_state(
        k in key_arb(), //key state is stored at
        v in value_arb(), //value in contract state
        name in "\\PC*", //human-readable name for state
        missing_name in "\\PC*",
        body in vec(any::<u8>(), 1..1000), //contract body
        hash in u8_slice_32(), //hash for contract key
      ) {
          let mut map = BTreeMap::new();
          map.insert(k, v.clone());

          let mut known_urefs = BTreeMap::new();
          known_urefs.insert(name.clone(), k);
          let contract = Value::Contract {
            bytes: body,
            known_urefs,
          };
          let contract_key = Key::Hash(hash);
          map.insert(contract_key, contract);

          let gs = InMemGS::new(map);
          let mut tc = TrackingCopy::new(gs);
          let path = vec!(name.clone());
          if let Ok(QueryResult::Success(result)) = query_tc(&mut tc, contract_key, &path) {
            assert_eq!(v, result);
          } else {
            panic!("Query failed when it should not have!");
          }

          if missing_name != name {
            let result = query_tc(&mut tc, contract_key, &vec!(missing_name));
            assert_matches!(result, Ok(QueryResult::ValueNotFound(_)));
          }
      }


      #[test]
      fn query_account_state(
        k in key_arb(), //key state is stored at
        v in value_arb(), //value in contract state
        name in "\\PC*", //human-readable name for state
        missing_name in "\\PC*",
        pk in u8_slice_32(), //account public key
        nonce in any::<u64>(), //account nonce
        address in u8_slice_20(), //addres for account key
      ) {
          let mut map = BTreeMap::new();
          map.insert(k, v.clone());

          let mut known_urefs = BTreeMap::new();
          known_urefs.insert(name.clone(), k);
          let account = Account::new(
            pk,
            nonce,
            known_urefs,
          );
          let account_key = Key::Account(address);
          map.insert(account_key, Value::Acct(account));

          let gs = InMemGS::new(map);
          let mut tc = TrackingCopy::new(gs);
          let path = vec!(name.clone());
          if let Ok(QueryResult::Success(result)) = query_tc(&mut tc, account_key, &path) {
            assert_eq!(v, result);
          } else {
            panic!("Query failed when it should not have!");
          }

          if missing_name != name {
            let result = query_tc(&mut tc, account_key, &vec!(missing_name));
            assert_matches!(result, Ok(QueryResult::ValueNotFound(_)));
          }
      }

      #[test]
      fn query_path(
        k in key_arb(), //key state is stored at
        v in value_arb(), //value in contract state
        state_name in "\\PC*", //human-readable name for state
        contract_name in "\\PC*", //human-readable name for contract
        pk in u8_slice_32(), //account public key
        nonce in any::<u64>(), //account nonce
        address in u8_slice_20(), //addres for account key
        body in vec(any::<u8>(), 1..1000), //contract body
        hash in u8_slice_32(), //hash for contract key
      ) {
          let mut map = BTreeMap::new();
          map.insert(k, v.clone());

          //create contract which knows about value
          let mut contract_known_urefs = BTreeMap::new();
          contract_known_urefs.insert(state_name.clone(), k);
          let contract = Value::Contract {
            bytes: body,
            known_urefs: contract_known_urefs,
          };
          let contract_key = Key::Hash(hash);
          map.insert(contract_key, contract);

          //create account which knows about contract
          let mut account_known_urefs = BTreeMap::new();
          account_known_urefs.insert(contract_name.clone(), contract_key);
          let account = Account::new(
            pk,
            nonce,
            account_known_urefs,
          );
          let account_key = Key::Account(address);
          map.insert(account_key, Value::Acct(account));

          let gs = InMemGS::new(map);
          let mut tc = TrackingCopy::new(gs);
          let path = vec!(contract_name, state_name);
          if let Ok(QueryResult::Success(result)) = query_tc(&mut tc, account_key, &path) {
            assert_eq!(v, result);
          } else {
            panic!("Query failed when it should not have!");
          }
      }
    }
}
