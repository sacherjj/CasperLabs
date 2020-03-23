#![no_std]

extern crate alloc;

pub mod error;
pub mod game_move;
pub mod game_state;
pub mod player;

use error::Error;
use game_move::{Move, MoveOutcome};
use game_state::{CellState, GameState};
use player::Player;

pub fn take_turn(game_state: &GameState, player_move: Move) -> Result<MoveOutcome, Error> {
    if game_state.active_player != player_move.player {
        return Err(Error::NotActivePlayer);
    }

    let index = GameState::xy_to_index(player_move.row_position, player_move.column_position);
    if index >= game_state::N_CELLS {
        return Err(Error::InvalidPosition);
    }

    if game_state.board[index] != CellState::Empty {
        return Err(Error::PositionAlreadyFilled);
    }

    let mut new_board = game_state.board;
    new_board[index] = player_move.player.into();

    let new_state = GameState {
        active_player: game_state.active_player.other(),
        board: new_board,
    };

    if let Some(player) = find_winner(&new_state) {
        return Ok(MoveOutcome::Winner(new_state, player));
    }

    match new_state.board.iter().find(|r| **r == CellState::Empty) {
        None => Ok(MoveOutcome::Draw(new_state)),
        Some(_) => Ok(MoveOutcome::Continue(new_state)),
    }
}

fn find_three_in_a_row<'a, S, T>(mut cells: S) -> Option<CellState>
where
    T: IntoIterator<Item = &'a CellState>,
    S: Iterator<Item = T>,
{
    cells.find_map(|r| {
        let mut iter = r.into_iter().peekable();
        let head = match iter.peek() {
            None => return None,
            Some(CellState::Empty) => return None,
            Some(head) => **head,
        };
        match iter.find(|cell| **cell != head) {
            None => Some(head),
            Some(_) => None,
        }
    })
}

fn find_winner(game_state: &GameState) -> Option<Player> {
    let three_in_a_row = find_three_in_a_row(game_state.rows().iter().map(|r| r.iter()))
        .or_else(|| find_three_in_a_row(game_state.columns().iter().map(|r| r.iter())))
        .or_else(|| find_three_in_a_row(game_state.diagonals().iter().map(|r| r.iter())));

    three_in_a_row.and_then(|r| match r {
        CellState::Empty => None,
        CellState::X => Some(Player::X),
        CellState::O => Some(Player::O),
    })
}

#[cfg(test)]
mod tests {
    use crate::{
        game_move::{Move, MoveOutcome},
        game_state::{tests::board_from_str, GameState},
        player::Player,
    };

    #[test]
    fn should_detect_winner_properly() {
        let state = GameState {
            active_player: Player::X,
            board: board_from_str("XO OX    "),
        };
        let x_move = Move {
            player: Player::X,
            row_position: 2,
            column_position: 2,
        };
        let result = super::take_turn(&state, x_move).expect("Move should be valid");
        let expected_state = GameState {
            active_player: Player::O,
            board: board_from_str("XO OX   X"),
        };
        assert_eq!(result, MoveOutcome::Winner(expected_state, Player::X));
    }

    #[test]
    fn should_detect_draw_properly() {
        let state = GameState {
            active_player: Player::X,
            board: board_from_str("OOXXXOOX "),
        };
        let x_move = Move {
            player: Player::X,
            row_position: 2,
            column_position: 2,
        };
        let result = super::take_turn(&state, x_move).expect("Move should be valid");
        let expected_state = GameState {
            active_player: Player::O,
            board: board_from_str("OOXXXOOXX"),
        };
        assert_eq!(result, MoveOutcome::Draw(expected_state));
    }
}
