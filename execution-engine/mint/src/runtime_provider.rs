use types::{account::PublicKey, Key};

pub trait RuntimeProvider {
    fn get_caller(&self) -> PublicKey;

    fn put_key(&mut self, name: &str, key: Key);
}
