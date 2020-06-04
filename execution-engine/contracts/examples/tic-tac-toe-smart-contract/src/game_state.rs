use alloc::{
    format,
    string::{String, ToString},
    vec::Vec,
};
use core::convert::TryInto;

use num_traits::{FromPrimitive, ToPrimitive};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use tic_tac_toe_logic::game_state::{CellState, GameState, N_CELLS};
use types::account::AccountHash;

use crate::{error::Error, state_key::StateKey};

const GAME_STATE_BYTES_SIZE: usize = N_CELLS + 1;

pub fn read_local(x_player: AccountHash, o_player: AccountHash) -> Option<GameState> {
    let state_key = StateKey::new(x_player, o_player);
    let value: Option<Vec<u8>> =
        storage::read_local(&state_key).unwrap_or_revert_with(Error::GameStateDeserialization);
    value.and_then(from_value)
}

pub fn write_local(x_player: AccountHash, o_player: AccountHash, state: &GameState) {
    let state_key = StateKey::new(x_player, o_player);
    let value = to_value(state).unwrap_or_revert();
    storage::write_local(state_key, value);
}

pub fn game_status_key(a: &AccountHash, b: &AccountHash) -> String {
    if a > b {
        format!("Game {} vs {}", a, b)
    } else {
        format!("Game {} vs {}", b, a)
    }
}

pub fn update_game_status(state: &GameState, x_player: AccountHash, o_player: AccountHash) {
    let name = game_status_key(&x_player, &o_player);
    let key = runtime::get_key(&name).unwrap_or_revert();
    let uref = key.try_into().unwrap_or_revert();
    storage::write(uref, (*state).to_string());
}

fn to_value(state: &GameState) -> Option<Vec<u8>> {
    let player_byte = state.active_player.to_u8()?;
    let mut bytes = Vec::with_capacity(GAME_STATE_BYTES_SIZE);
    bytes.push(player_byte);
    for cell in state.board.iter() {
        bytes.push(cell.to_u8()?);
    }
    Some(bytes)
}

fn from_value(value: Vec<u8>) -> Option<GameState> {
    if value.len() != GAME_STATE_BYTES_SIZE {
        None
    } else {
        let active_player = FromPrimitive::from_u8(value[0])?;
        let mut board = [CellState::Empty; N_CELLS];
        for i in 0..N_CELLS {
            board[i] = FromPrimitive::from_u8(value[i + 1])?;
        }
        Some(GameState {
            active_player,
            board,
        })
    }
}

#[cfg(test)]
mod tests {
    use tic_tac_toe_logic::{
        game_state::{CellState, GameState, N_CELLS},
        player::Player,
    };

    #[test]
    fn game_state_round_trip() {
        let state = GameState {
            active_player: Player::O,
            board: [CellState::X; N_CELLS],
        };
        let value = super::to_value(&state).expect("Should serialize");
        let state_2 = super::from_value(value).expect("Should deserialize");
        assert_eq!(state_2, state);
    }
}
