use contract::contract_api::{storage, TURef};
use types::{
    bytesrepr::{Error, FromBytes, ToBytes},
    CLTyped,
};

pub trait StorageProvider {
    fn new_turef<T: CLTyped + ToBytes>(init: T) -> TURef<T>;

    fn write_local<K: ToBytes, V: CLTyped + ToBytes>(key: K, value: V) -> ();

    fn read_local<K: ToBytes, V: CLTyped + FromBytes>(key: &K) -> Result<Option<V>, Error>;

    fn read<T: CLTyped + FromBytes>(turef: TURef<T>) -> Result<Option<T>, Error>;

    fn write<T: CLTyped + ToBytes>(turef: TURef<T>, value: T);

    fn add<T: CLTyped + ToBytes>(turef: TURef<T>, value: T);
}

pub struct ContractStorage;

impl StorageProvider for ContractStorage {
    fn new_turef<T: CLTyped + ToBytes>(init: T) -> TURef<T> {
        storage::new_turef(init)
    }

    fn write_local<K: ToBytes, V: CLTyped + ToBytes>(key: K, value: V) {
        storage::write_local(key, value)
    }

    fn read_local<K: ToBytes, V: CLTyped + FromBytes>(key: &K) -> Result<Option<V>, Error> {
        storage::read_local(key)
    }

    fn read<T: CLTyped + FromBytes>(turef: TURef<T>) -> Result<Option<T>, Error> {
        storage::read(turef)
    }

    fn write<T: CLTyped + ToBytes>(turef: TURef<T>, value: T) {
        storage::write(turef, value)
    }

    fn add<T: CLTyped + ToBytes>(turef: TURef<T>, value: T) {
        storage::add(turef, value)
    }
}
