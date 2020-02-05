use types::{account::PublicKey, Key};

pub trait RuntimeProvider {
    fn get_caller() -> PublicKey;

    fn put_key(name: &str, key: Key) -> ();
}
