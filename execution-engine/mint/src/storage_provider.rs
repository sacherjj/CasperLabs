use types::{
    bytesrepr::{FromBytes, ToBytes},
    system_contract_errors::mint::Error,
    CLTyped, URef,
};

pub trait StorageProvider {
    fn new_uref<T: CLTyped + ToBytes>(init: T) -> URef;

    fn write_local<K: ToBytes, V: CLTyped + ToBytes>(key: K, value: V) -> ();

    fn read_local<K: ToBytes, V: CLTyped + FromBytes>(key: &K) -> Result<Option<V>, Error>;

    fn read<T: CLTyped + FromBytes>(uref: URef) -> Result<Option<T>, Error>;

    fn write<T: CLTyped + ToBytes>(uref: URef, value: T) -> Result<(), Error>;

    fn add<T: CLTyped + ToBytes>(uref: URef, value: T) -> Result<(), Error>;
}
