use alloc::string::String;
use core::fmt::{Display, Formatter};

use num_derive::{FromPrimitive, ToPrimitive};

use crate::player::Player;

const N_ROWS: usize = 3;
const N_COLUMNS: usize = 3;
pub const N_CELLS: usize = N_ROWS * N_COLUMNS;

#[repr(u8)]
#[derive(PartialEq, Eq, Copy, Clone, Debug, FromPrimitive, ToPrimitive)]
pub enum CellState {
    Empty = 0,
    X = 1,
    O = 2,
}

impl From<Player> for CellState {
    fn from(player: Player) -> CellState {
        match player {
            Player::X => CellState::X,
            Player::O => CellState::O,
        }
    }
}

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct GameState {
    pub active_player: Player,
    pub board: [CellState; N_CELLS],
}

impl Default for GameState {
    fn default() -> Self {
        Self::new()
    }
}

impl GameState {
    // covert (x, y) coordinate to linear index,
    // where the top left of the board is (0, 0) -> 0
    // and cells are numbered row-wise.
    pub fn xy_to_index(row: usize, column: usize) -> usize {
        N_COLUMNS * row + column
    }

    pub fn new() -> GameState {
        GameState {
            active_player: Player::X,
            board: [CellState::Empty; N_CELLS],
        }
    }

    fn fill_row(&self, start_index: usize) -> [CellState; N_COLUMNS] {
        let mut result = [CellState::Empty; N_COLUMNS];

        for (i, cell) in result.iter_mut().enumerate() {
            *cell = self.board[i + start_index];
        }

        result
    }

    fn fill_col(&self, start_index: usize) -> [CellState; N_ROWS] {
        let mut result = [CellState::Empty; N_ROWS];

        for (i, cell) in result.iter_mut().enumerate() {
            *cell = self.board[i * N_COLUMNS + start_index];
        }

        result
    }

    fn fill_diag(&self, start_index: usize, step: usize) -> [CellState; N_ROWS] {
        let mut result = [CellState::Empty; N_ROWS];

        for (i, cell) in result.iter_mut().enumerate() {
            *cell = self.board[i * step + start_index];
        }

        result
    }

    pub fn rows(&self) -> [[CellState; N_COLUMNS]; N_ROWS] {
        let mut result = [[CellState::Empty; N_COLUMNS]; N_ROWS];

        for (i, row) in result.iter_mut().enumerate() {
            *row = self.fill_row(N_COLUMNS * i);
        }

        result
    }

    pub fn columns(&self) -> [[CellState; N_ROWS]; N_COLUMNS] {
        let mut result = [[CellState::Empty; N_ROWS]; N_COLUMNS];

        for (i, col) in result.iter_mut().enumerate() {
            *col = self.fill_col(i);
        }

        result
    }

    pub fn diagonals(&self) -> [[CellState; N_ROWS]; 2] {
        let mut result = [[CellState::Empty; N_ROWS]; 2];

        result[0] = self.fill_diag(0, N_COLUMNS + 1);
        result[1] = self.fill_diag(N_COLUMNS - 1, N_COLUMNS - 1);

        result
    }
}

impl Display for GameState {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        let mut result = String::with_capacity(self.board.len());
        for cell in self.board.iter() {
            result.push(match cell {
                CellState::Empty => '_',
                CellState::X => 'X',
                CellState::O => 'O',
            })
        }
        write!(f, "{}", result)
    }
}

#[cfg(test)]
pub mod tests {
    use super::{CellState, GameState, N_CELLS};
    use crate::player::Player;

    pub fn board_from_str(s: &str) -> [CellState; N_CELLS] {
        let mut result = [CellState::Empty; N_CELLS];

        s.chars().enumerate().for_each(|(i, c)| match c {
            ' ' => result[i] = CellState::Empty,
            'X' => result[i] = CellState::X,
            'O' => result[i] = CellState::O,
            _ => panic!("Unexpected character"),
        });

        result
    }

    #[test]
    fn should_get_rows_properly() {
        let state = GameState {
            active_player: Player::X,
            board: board_from_str("XX OO    "),
        };

        let rows = state.rows();

        assert_eq!(rows[0], [CellState::X, CellState::X, CellState::Empty]);
        assert_eq!(rows[1], [CellState::O, CellState::O, CellState::Empty]);
        assert_eq!(
            rows[2],
            [CellState::Empty, CellState::Empty, CellState::Empty]
        );
    }

    #[test]
    fn should_get_columns_properly() {
        let state = GameState {
            active_player: Player::X,
            board: board_from_str("XX OO    "),
        };

        let columns = state.columns();

        assert_eq!(columns[0], [CellState::X, CellState::O, CellState::Empty]);
        assert_eq!(columns[1], [CellState::X, CellState::O, CellState::Empty]);
        assert_eq!(
            columns[2],
            [CellState::Empty, CellState::Empty, CellState::Empty]
        );
    }

    #[test]
    fn should_get_diagonals_properly() {
        let state = GameState {
            active_player: Player::X,
            board: board_from_str("XX OO    "),
        };

        let diagonals = state.diagonals();

        assert_eq!(diagonals[0], [CellState::X, CellState::O, CellState::Empty]);
        assert_eq!(
            diagonals[1],
            [CellState::Empty, CellState::O, CellState::Empty]
        );
    }
}
