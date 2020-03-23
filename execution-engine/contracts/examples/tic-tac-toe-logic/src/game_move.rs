use crate::{game_state::GameState, player::Player};

#[derive(PartialEq, Eq, Debug, Copy, Clone)]
pub struct Move {
    pub player: Player,
    pub row_position: usize,
    pub column_position: usize,
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum MoveOutcome {
    Continue(GameState),
    Winner(GameState, Player),
    Draw(GameState),
}
