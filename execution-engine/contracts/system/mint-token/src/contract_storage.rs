use contract::contract_api::storage;
use mint::StorageProvider;
use types::{
    bytesrepr::{FromBytes, ToBytes},
    system_contract_errors::mint::Error,
    CLTyped, URef,
};

pub struct ContractStorage;

impl StorageProvider for ContractStorage {
    fn new_uref<T: CLTyped + ToBytes>(init: T) -> URef {
        storage::new_uref(init)
    }

    fn write_local<K: ToBytes, V: CLTyped + ToBytes>(key: K, value: V) {
        storage::write_local(key, value)
    }

    fn read_local<K: ToBytes, V: CLTyped + FromBytes>(key: &K) -> Result<Option<V>, Error> {
        storage::read_local(key).map_err(|_| Error::Storage)
    }

    fn read<T: CLTyped + FromBytes>(uref: URef) -> Result<Option<T>, Error> {
        storage::read(uref).map_err(|_| Error::Storage)
    }

    fn write<T: CLTyped + ToBytes>(uref: URef, value: T) -> Result<(), Error> {
        storage::write(uref, value);
        Ok(())
    }

    fn add<T: CLTyped + ToBytes>(uref: URef, value: T) -> Result<(), Error> {
        storage::add(uref, value);
        Ok(())
    }
}
