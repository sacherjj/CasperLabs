use text_io::try_scan;

use tic_tac_toe_logic::{
    game_move::{Move, MoveOutcome},
    game_state::{CellState, GameState},
    player::Player,
};

fn parse() -> Result<(usize, usize), Box<text_io::Error>> {
    let i: usize;
    let j: usize;

    try_scan!("{},{}\n", i, j);

    Ok((i, j))
}

fn get_input(player: Player) -> Option<Move> {
    let (row_position, column_position) = parse().ok()?;
    Some(Move {
        player,
        row_position,
        column_position,
    })
}

fn print_board(game_state: &GameState) {
    let rows = game_state.rows();

    for row in rows.iter() {
        row.iter().for_each(|cell| match cell {
            CellState::Empty => print!(" |"),
            CellState::X => print!("X|"),
            CellState::O => print!("O|"),
        });
        println!();
        row.iter().for_each(|_| print!("--"));
        println!();
    }
}

fn game_loop() {
    let mut state = GameState::new();
    loop {
        let current_player = state.active_player;
        print_board(&state);
        println!("{:?}, it's your turn!", current_player);
        let current_move = match get_input(current_player) {
            Some(m) => m,
            None => {
                println!("Invalid input, try again!");
                continue;
            }
        };

        match tic_tac_toe_logic::take_turn(&state, current_move) {
            Err(error) => {
                println!("Invalid move: {:?}", error);
            }

            Ok(MoveOutcome::Draw(_)) => {
                println!("It's a draw!");
                break;
            }

            Ok(MoveOutcome::Winner(_, _)) => {
                println!("{:?}, you win!", current_player);
                break;
            }

            Ok(MoveOutcome::Continue(new_state)) => {
                state = new_state;
            }
        }
    }
}

fn main() {
    println!("Welcome to Tic Tac Toe!");
    println!("Type your move in the format of X,Y. Eg.: 0,1 or 2,1.");
    game_loop();
}
