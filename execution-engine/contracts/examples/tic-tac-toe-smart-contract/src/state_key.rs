use alloc::vec::Vec;

use types::{
    account::AccountHash,
    bytesrepr::{self, ToBytes},
};

pub struct StateKey([u8; 64]);

impl StateKey {
    pub fn new(x_player: AccountHash, o_player: AccountHash) -> StateKey {
        let mut result = [0u8; 64];
        for (i, j) in x_player
            .as_bytes()
            .iter()
            .chain(o_player.as_bytes().iter())
            .enumerate()
        {
            result[i] = *j;
        }
        StateKey(result)
    }
}

impl ToBytes for StateKey {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        self.0.to_bytes()
    }

    fn serialized_length(&self) -> usize {
        self.0.serialized_length()
    }
}
