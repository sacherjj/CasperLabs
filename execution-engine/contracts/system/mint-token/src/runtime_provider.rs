use contract::contract_api::runtime;
use types::{account::PublicKey, Key};

pub trait RuntimeProvider {
    fn get_caller() -> PublicKey;

    fn put_key(name: &str, key: Key) -> ();
}

pub struct ContractRuntime;

impl RuntimeProvider for ContractRuntime {
    fn get_caller() -> PublicKey {
        runtime::get_caller()
    }

    fn put_key(name: &str, key: Key) {
        runtime::put_key(name, key)
    }
}
