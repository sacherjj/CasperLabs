use contract::contract_api::{storage, TURef};
use mint::StorageProvider;
use types::{
    bytesrepr::{FromBytes, ToBytes},
    system_contract_errors::mint::Error,
    CLTyped, URef,
};

pub struct ContractStorage;

impl StorageProvider for ContractStorage {
    fn new_uref<T: CLTyped + ToBytes>(init: T) -> URef {
        storage::new_turef(init).into()
    }

    fn write_local<K: ToBytes, V: CLTyped + ToBytes>(key: K, value: V) {
        storage::write_local(key, value)
    }

    fn read_local<K: ToBytes, V: CLTyped + FromBytes>(key: &K) -> Result<Option<V>, Error> {
        storage::read_local(key).map_err(|_| Error::Storage)
    }

    fn read<T: CLTyped + FromBytes>(uref: URef) -> Result<Option<T>, Error> {
        let turef: TURef<T> = TURef::from_uref(uref).map_err(|_| Error::InvalidAccessRights)?;
        storage::read(turef).map_err(|_| Error::Storage)
    }

    fn write<T: CLTyped + ToBytes>(uref: URef, value: T) -> Result<(), Error> {
        let turef: TURef<T> = TURef::from_uref(uref).map_err(|_| Error::InvalidAccessRights)?;
        storage::write(turef, value);
        Ok(())
    }

    fn add<T: CLTyped + ToBytes>(uref: URef, value: T) -> Result<(), Error> {
        let turef: TURef<T> = TURef::from_uref(uref).map_err(|_| Error::InvalidAccessRights)?;
        storage::add(turef, value);
        Ok(())
    }
}
