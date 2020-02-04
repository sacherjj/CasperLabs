use contract::contract_api::runtime;
use proof_of_stake::RuntimeProvider;
use types::{account::PublicKey, key::Key, BlockTime, Phase};

pub struct ContractRuntime;

impl RuntimeProvider for ContractRuntime {
    fn get_key(name: &str) -> Option<Key> {
        runtime::get_key(name)
    }

    fn put_key(name: &str, key: Key) {
        runtime::put_key(name, key)
    }

    fn remove_key(name: &str) {
        runtime::remove_key(name)
    }

    fn get_phase() -> Phase {
        runtime::get_phase()
    }

    fn get_block_time() -> BlockTime {
        runtime::get_blocktime()
    }

    fn get_caller() -> PublicKey {
        runtime::get_caller()
    }
}
