use types::{account::PublicKey, key::Key, BlockTime, Phase};

pub trait RuntimeProvider {
    fn get_key(name: &str) -> Option<Key>;

    fn put_key(name: &str, key: Key) -> ();

    fn remove_key(name: &str) -> ();

    fn get_phase() -> Phase;

    fn get_block_time() -> BlockTime;

    fn get_caller() -> PublicKey;
}
