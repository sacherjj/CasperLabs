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
    mut tc: TrackingCopy<R>,
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
