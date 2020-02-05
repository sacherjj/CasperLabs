use contract::contract_api::runtime;
use mint::RuntimeProvider;
use types::{account::PublicKey, Key};

pub struct ContractRuntime;

impl RuntimeProvider for ContractRuntime {
    fn get_caller() -> PublicKey {
        runtime::get_caller()
    }

    fn put_key(name: &str, key: Key) {
        runtime::put_key(name, key)
    }
}
