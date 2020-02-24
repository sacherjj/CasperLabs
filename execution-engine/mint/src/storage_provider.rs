use types::{
    bytesrepr::{FromBytes, ToBytes},
    system_contract_errors::mint::Error,
    CLTyped, URef,
};

pub trait StorageProvider {
    fn new_uref<T: CLTyped + ToBytes>(&mut self, init: T) -> URef;

    fn write_local<K: ToBytes, V: CLTyped + ToBytes>(&mut self, key: K, value: V);

    fn read_local<K: ToBytes, V: CLTyped + FromBytes>(
        &mut self,
        key: &K,
    ) -> Result<Option<V>, Error>;

    fn read<T: CLTyped + FromBytes>(&mut self, uref: URef) -> Result<Option<T>, Error>;

    fn write<T: CLTyped + ToBytes>(&mut self, uref: URef, value: T) -> Result<(), Error>;

    fn add<T: CLTyped + ToBytes>(&mut self, uref: URef, value: T) -> Result<(), Error>;
}
