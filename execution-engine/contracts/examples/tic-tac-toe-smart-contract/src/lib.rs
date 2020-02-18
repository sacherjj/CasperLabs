#![no_std]

extern crate alloc;

use alloc::{format, string::ToString};

use contract::{
    contract_api::{runtime, storage, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, Key};

use tic_tac_toe_logic::{
    game_move::{Move, MoveOutcome},
    game_state::GameState,
    player::Player,
};

mod api;
mod error;
mod game_state;
mod player_data;
mod state_key;

use api::Api;
use error::Error;
use player_data::PlayerData;

const GAME_CONTRACT_NAME: &str = "tic_tac_toe";
const GAME_PROXY_CONTRACT_NAME: &str = "tic_tac_toe_proxy";

fn start_game(x_player: PublicKey, o_player: PublicKey) -> Result<(), Error> {
    if PlayerData::read_local(x_player).is_some() {
        return Err(Error::AlreadyPlaying);
    }

    if PlayerData::read_local(o_player).is_some() {
        return Err(Error::AlreadyPlaying);
    }

    let state = GameState::new();
    let x_status = storage::new_turef(format!("playing as X against {}", o_player));
    let o_status = storage::new_turef(format!("playing as O against {}", x_player));
    let game_status = storage::new_turef(state.to_string());
    let game_status_key = game_state::game_status_key(&x_player, &o_player);

    game_state::write_local(x_player, o_player, &state);
    PlayerData::write_local(x_player, Player::X, o_player, x_status.clone());
    PlayerData::write_local(o_player, Player::O, x_player, o_status.clone());

    runtime::put_key(x_player.to_string().as_str(), x_status.into());
    runtime::put_key(o_player.to_string().as_str(), o_status.into());
    runtime::put_key(&game_status_key, game_status.into());
    Ok(())
}

fn take_turn(player: PublicKey, row_position: usize, column_position: usize) -> Result<(), Error> {
    let player_data = PlayerData::read_local(player).ok_or(Error::NoGameFoundForPlayer)?;

    let (x_player, o_player) = if player_data.piece() == Player::X {
        (player, player_data.opponent())
    } else {
        (player_data.opponent(), player)
    };

    let state = game_state::read_local(x_player, o_player).ok_or(Error::NoGameFoundForPlayer)?;
    let player_move = Move {
        player: player_data.piece(),
        row_position,
        column_position,
    };

    match tic_tac_toe_logic::take_turn(&state, player_move) {
        Err(_) => Err(Error::GameError),
        Ok(MoveOutcome::Draw(new_state)) => {
            complete_game(x_player, o_player, None);
            game_state::update_game_status(&new_state, x_player, o_player);
            Ok(())
        }
        Ok(MoveOutcome::Winner(new_state, winner)) => {
            complete_game(x_player, o_player, Some(winner));
            game_state::update_game_status(&new_state, x_player, o_player);
            Ok(())
        }
        Ok(MoveOutcome::Continue(new_state)) => {
            game_state::write_local(x_player, o_player, &new_state);
            game_state::update_game_status(&new_state, x_player, o_player);
            Ok(())
        }
    }
}

fn complete_game(x_player: PublicKey, o_player: PublicKey, winner: Option<Player>) {
    let x_player_data = PlayerData::read_local(x_player).unwrap_or_revert();
    let o_player_data = PlayerData::read_local(o_player).unwrap_or_revert();

    let x_str = x_player.to_string();
    let o_str = o_player.to_string();

    let x_status = match winner {
        None => format!("draw against {}", o_str),
        Some(Player::X) => format!("victorious against {}", o_str),
        Some(Player::O) => format!("defeated by {}", o_str),
    };

    let o_status = match winner {
        None => format!("draw against {}", x_str),
        Some(Player::O) => format!("victorious against {}", x_str),
        Some(Player::X) => format!("defeated by {}", x_str),
    };

    storage::write(x_player_data.status_key(), x_status);
    storage::write(o_player_data.status_key(), o_status);

    storage::write_local(x_player, ());
    storage::write_local(o_player, ());
}

fn concede(player: PublicKey) -> Result<(), Error> {
    let player_data = PlayerData::read_local(player).ok_or(Error::NoGameFoundForPlayer)?;
    let (x_player, o_player) = if player_data.piece() == Player::X {
        (player, player_data.opponent())
    } else {
        (player_data.opponent(), player)
    };
    complete_game(x_player, o_player, Some(player_data.piece().other()));
    Ok(())
}

fn deploy() {
    let game_hash = storage::store_function_at_hash(GAME_CONTRACT_NAME, Default::default());
    let proxy_hash = storage::store_function_at_hash(GAME_PROXY_CONTRACT_NAME, Default::default());

    let game_turef: TURef<Key> = storage::new_turef(game_hash.into());
    let proxy_turef: TURef<Key> = storage::new_turef(proxy_hash.into());

    runtime::put_key(GAME_CONTRACT_NAME, game_turef.into());
    runtime::put_key(GAME_PROXY_CONTRACT_NAME, proxy_turef.into());
}

#[no_mangle]
pub extern "C" fn tic_tac_toe() {
    match Api::from_args() {
        Api::Start(x_player, o_player) => start_game(x_player, o_player),
        Api::Move(row_position, column_position) => {
            let player = runtime::get_caller();
            take_turn(player, row_position as usize, column_position as usize)
        }
        Api::Concede => {
            let player = runtime::get_caller();
            concede(player)
        }
    }
    .unwrap_or_revert()
}

#[no_mangle]
pub extern "C" fn tic_tac_toe_proxy() {
    let game_contract = Api::destination_contract();
    match Api::from_args_in_proxy() {
        Api::Start(x_player, o_player) => {
            runtime::call_contract(game_contract, (api::START, x_player, o_player))
        }
        Api::Move(row_position, column_position) => {
            runtime::call_contract(game_contract, (api::MOVE, row_position, column_position))
        }
        Api::Concede => runtime::call_contract(game_contract, (api::CONCEDE,)),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    deploy();
}
