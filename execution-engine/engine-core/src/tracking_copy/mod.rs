mod byte_size;
mod ext;
pub(self) mod meter;
#[cfg(test)]
mod tests;
pub mod utils;

use std::collections::{BTreeMap, HashMap};

use linked_hash_map::LinkedHashMap;
use parking_lot::Mutex;

use contract_ffi::key::Key;
use contract_ffi::value::Value;
use engine_shared::newtypes::{CorrelationId, Validated};
use engine_shared::transform::{self, Transform, TypeMismatch};
use engine_storage::global_state::StateReader;

pub use self::ext::TrackingCopyExt;
use self::meter::heap_meter::HeapSize;
use self::meter::Meter;
use crate::engine_state::execution_effect::ExecutionEffect;
use crate::engine_state::op::Op;

#[derive(Debug)]
pub enum QueryResult {
    Success(Value),
    ValueNotFound(String),
}

/// Keeps track of already accessed keys.
/// We deliberately separate cached Reads from cached mutations
/// because we want to invalidate Reads' cache so it doesn't grow too fast.
pub struct TrackingCopyCache<M> {
    max_cache_size: usize,
    current_cache_size: Mutex<usize>,
    reads_cached: LinkedHashMap<Key, Value>,
    muts_cached: HashMap<Key, Value>,
    meter: M,
}

impl<M: Meter<Key, Value>> TrackingCopyCache<M> {
    /// Creates instance of `TrackingCopyCache` with specified `max_cache_size`,
    /// above which least-recently-used elements of the cache are invalidated.
    /// Measurements of elements' "size" is done with the usage of `Meter`
    /// instance.
    pub fn new(max_cache_size: usize, meter: M) -> TrackingCopyCache<M> {
        TrackingCopyCache {
            max_cache_size,
            current_cache_size: Mutex::new(0),
            reads_cached: LinkedHashMap::new(),
            muts_cached: HashMap::new(),
            meter,
        }
    }

    /// Inserts `key` and `value` pair to Read cache.
    pub fn insert_read(&mut self, key: Key, value: Value) {
        let element_size = Meter::measure(&self.meter, &key, &value);
        self.reads_cached.insert(key, value);
        *self.current_cache_size.lock() += element_size;
        while *self.current_cache_size.lock() > self.max_cache_size {
            match self.reads_cached.pop_front() {
                Some((k, v)) => {
                    let element_size = Meter::measure(&self.meter, &k, &v);
                    *self.current_cache_size.lock() -= element_size;
                }
                None => break,
            }
        }
    }

    /// Inserts `key` and `value` pair to Write/Add cache.
    pub fn insert_write(&mut self, key: Key, value: Value) {
        self.muts_cached.insert(key, value.clone());
    }

    /// Gets value from `key` in the cache.
    pub fn get(&mut self, key: &Key) -> Option<&Value> {
        if let Some(value) = self.muts_cached.get(&key) {
            return Some(value);
        };

        self.reads_cached.get_refresh(key).map(|v| &*v)
    }

    pub fn is_empty(&self) -> bool {
        self.reads_cached.is_empty() && self.muts_cached.is_empty()
    }
}

pub struct TrackingCopy<R> {
    reader: R,
    cache: TrackingCopyCache<HeapSize>,
    ops: HashMap<Key, Op>,
    fns: HashMap<Key, Transform>,
}

#[derive(Debug)]
pub enum AddResult {
    Success,
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
}

impl<R: StateReader<Key, Value>> TrackingCopy<R> {
    pub fn new(reader: R) -> TrackingCopy<R> {
        TrackingCopy {
            reader,
            cache: TrackingCopyCache::new(1024 * 16, HeapSize), /* TODO: Should `max_cache_size`
                                                                 * be fraction of Wasm memory
                                                                 * limit? */
            ops: HashMap::new(),
            fns: HashMap::new(),
        }
    }

    pub fn reader(&self) -> &R {
        &self.reader
    }

    /// Creates a new TrackingCopy, using this one (including its mutations) as
    /// the base state to read against. The intended use case for this
    /// function is to "snapshot" the current `TrackingCopy` and produce a
    /// new `TrackingCopy` where further changes can be made. This
    /// allows isolating a specific set of changes (those in the new
    /// `TrackingCopy`) from existing changes. Note that mutations to state
    /// caused by new changes (i.e. writes and adds) only impact the new
    /// `TrackingCopy`, not this one. Note that currently there is no `join` /
    /// `merge` function to bring changes from a fork back to the main
    /// `TrackingCopy`. this means the current usage requires repeated
    /// forking, however we recognize this is sub-optimal and will revisit
    /// in the future.
    pub fn fork(&self) -> TrackingCopy<&TrackingCopy<R>> {
        TrackingCopy::new(self)
    }

    pub fn get(
        &mut self,
        correlation_id: CorrelationId,
        k: &Key,
    ) -> Result<Option<Value>, R::Error> {
        if let Some(value) = self.cache.get(k) {
            return Ok(Some(value.to_owned()));
        }
        if let Some(value) = self.reader.read(correlation_id, k)? {
            self.cache.insert_read(*k, value.to_owned());
            Ok(Some(value))
        } else {
            Ok(None)
        }
    }

    pub fn read(
        &mut self,
        correlation_id: CorrelationId,
        k: &Validated<Key>,
    ) -> Result<Option<Value>, R::Error> {
        let k = k.normalize();
        if let Some(value) = self.get(correlation_id, &k)? {
            utils::add(&mut self.ops, k, Op::Read);
            utils::add(&mut self.fns, k, Transform::Identity);
            Ok(Some(value))
        } else {
            Ok(None)
        }
    }

    pub fn write(&mut self, k: Validated<Key>, v: Validated<Value>) {
        let v_local = v.into_raw();
        let k = k.normalize();
        self.cache.insert_write(k, v_local.clone());
        utils::add(&mut self.ops, k, Op::Write);
        utils::add(&mut self.fns, k, Transform::Write(v_local));
    }

    /// Ok(None) represents missing key to which we want to "add" some value.
    /// Ok(Some(unit)) represents successful operation.
    /// Err(error) is reserved for unexpected errors when accessing global
    /// state.
    pub fn add(
        &mut self,
        correlation_id: CorrelationId,
        k: Validated<Key>,
        v: Validated<Value>,
    ) -> Result<AddResult, R::Error> {
        let k = k.normalize();
        match self.get(correlation_id, &k)? {
            None => Ok(AddResult::KeyNotFound(k)),
            Some(curr) => {
                let t = match v.into_raw() {
                    Value::Int32(i) => Transform::AddInt32(i),
                    Value::UInt128(i) => Transform::AddUInt128(i),
                    Value::UInt256(i) => Transform::AddUInt256(i),
                    Value::UInt512(i) => Transform::AddUInt512(i),
                    Value::NamedKey(n, k) => {
                        let mut map = BTreeMap::new();
                        map.insert(n, k);
                        Transform::AddKeys(map)
                    }
                    other => {
                        return Ok(AddResult::TypeMismatch(TypeMismatch::new(
                            "Int32 or UInt* or NamedKey".to_string(),
                            other.type_string(),
                        )))
                    }
                };
                match t.clone().apply(curr) {
                    Ok(new_value) => {
                        self.cache.insert_write(k, new_value);
                        utils::add(&mut self.ops, k, Op::Add);
                        utils::add(&mut self.fns, k, t);
                        Ok(AddResult::Success)
                    }
                    Err(transform::Error::TypeMismatch(type_mismatch)) => {
                        Ok(AddResult::TypeMismatch(type_mismatch))
                    }
                }
            }
        }
    }

    pub fn effect(&self) -> ExecutionEffect {
        ExecutionEffect::new(self.ops.clone(), self.fns.clone())
    }

    pub fn query(
        &mut self,
        correlation_id: CorrelationId,
        base_key: Key,
        path: &[String],
    ) -> Result<QueryResult, R::Error> {
        let validated_key = Validated::new(base_key, Validated::valid)?;
        match self.read(correlation_id, &validated_key)? {
            None => Ok(QueryResult::ValueNotFound(self.error_path_msg(
                base_key,
                path,
                "".to_owned(),
                0 as usize,
            ))),
            Some(base_value) => {
                let result = path.iter().enumerate().try_fold(
                    base_value,
                    // We encode the two possible short-circuit conditions with
                    // Result<(usize, String), Error>, where the Ok(_) case corresponds to
                    // QueryResult::ValueNotFound and Err(_) corresponds to
                    // a storage-related error. The information in the Ok(_) case is used
                    // to build an informative error message about why the query was not successful.
                    |curr_value, (i, name)| -> Result<Value, Result<(usize, String), R::Error>> {
                        match curr_value {
                            Value::Account(account) => {
                                if let Some(key) = account.urefs_lookup().get(name) {
                                    let validated_key = Validated::new(*key, Validated::valid)?;
                                    self.read_key_or_stop(correlation_id, validated_key, i)
                                } else {
                                    Err(Ok((i, format!("Name {} not found in Account at path:", name))))
                                }
                            }

                            Value::Contract(contract) => {
                                if let Some(key) = contract.urefs_lookup().get(name) {
                                    let validated_key = Validated::new(*key, Validated::valid)?;
                                    self.read_key_or_stop(correlation_id, validated_key, i)
                                } else {
                                    Err(Ok((i, format!("Name {} not found in Contract at path:", name))))
                                }
                            }

                            other => Err(
                                Ok((i, format!("Name {} cannot be followed from value {:?} because it is neither an account nor contract. Value found at path:", name, other)))
                                ),
                        }
                    },
                );

                match result {
                    Ok(value) => Ok(QueryResult::Success(value)),
                    Err(Ok((i, s))) => Ok(QueryResult::ValueNotFound(
                        self.error_path_msg(base_key, path, s, i),
                    )),
                    Err(Err(err)) => Err(err),
                }
            }
        }
    }

    fn read_key_or_stop(
        &mut self,
        correlation_id: CorrelationId,
        key: Validated<Key>,
        i: usize,
    ) -> Result<Value, Result<(usize, String), R::Error>> {
        match self.read(correlation_id, &key) {
            // continue recursing
            Ok(Some(value)) => Ok(value),
            // key not found in the global state; stop recursing
            Ok(None) => Err(Ok((i, format!("Name {:?} not found: ", *key)))),
            // global state access error; stop recursing
            Err(error) => Err(Err(error)),
        }
    }

    fn error_path_msg(
        &self,
        key: Key,
        path: &[String],
        missing_key: String,
        missing_at_index: usize,
    ) -> String {
        let mut error_msg = format!("{} {:?}", missing_key, key);
        //include the partial path to the account/contract/value which failed
        for p in path.iter().take(missing_at_index) {
            error_msg.push_str("/");
            error_msg.push_str(p);
        }
        error_msg
    }
}

/// The purpose of this implementation is to allow a "snapshot" mechanism for
/// TrackingCopy. The state of a TrackingCopy (including the effects of
/// any transforms it has accumulated) can be read using an immutable
/// reference to that TrackingCopy via this trait implementation. See
/// `TrackingCopy::fork` for more information.
impl<R: StateReader<Key, Value>> StateReader<Key, Value> for &TrackingCopy<R> {
    type Error = R::Error;

    fn read(&self, correlation_id: CorrelationId, key: &Key) -> Result<Option<Value>, Self::Error> {
        if let Some(value) = self.cache.muts_cached.get(key) {
            return Ok(Some(value.to_owned()));
        }
        if let Some(value) = self.reader.read(correlation_id, key)? {
            Ok(Some(value))
        } else {
            Ok(None)
        }
    }
}
