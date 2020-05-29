extern crate alloc;

use engine_test_support::{
    AccountHash, Code, Hash, SessionBuilder, TestContext, TestContextBuilder,
};

use types::{Key, U512};

const GAME_WASM: &str = "tic_tac_toe_smart_contract.wasm";
const GAME_CONTRACT_NAME: &str = "tic_tac_toe";
const GAME_PROXY_CONTRACT_NAME: &str = "tic_tac_toe_proxy";

mod method {
    pub const START: &str = "start";
    pub const MOVE: &str = "move";
    pub const CONCEDE: &str = "concede";
}

const PLAYER_X: AccountHash = AccountHash::new([1u8; 32]);
const PLAYER_O: AccountHash = AccountHash::new([2u8; 32]);

pub struct GameTest {
    pub context: TestContext,
}

impl GameTest {
    pub fn new() -> Self {
        let initial_balance = U512::from(100_000_000_000u64);
        let context = TestContextBuilder::new()
            .with_account(PLAYER_X, initial_balance)
            .with_account(PLAYER_O, initial_balance)
            .build();
        GameTest { context }
    }

    pub fn deploy(mut self) -> Self {
        let code = Code::from(GAME_WASM);
        let session = SessionBuilder::new(code, ())
            .with_address(PLAYER_X)
            .with_authorization_keys(&[PLAYER_X])
            .build();
        self.context.run(session);
        self
    }

    pub fn start_game(mut self) -> Self {
        let proxy = Code::Hash(self.proxy_contract_hash());
        let args = (self.game_contract_hash(), method::START, PLAYER_X, PLAYER_O);
        let session = SessionBuilder::new(proxy, args)
            .with_address(PLAYER_X)
            .with_authorization_keys(&[PLAYER_X])
            .build();
        self.context.run(session);
        self
    }

    pub fn concede(mut self, player: AccountHash) -> Self {
        let proxy = Code::Hash(self.proxy_contract_hash());
        let args = (self.game_contract_hash(), method::CONCEDE);
        let session = SessionBuilder::new(proxy, args)
            .with_address(player)
            .with_authorization_keys(&[player])
            .build();
        self.context.run(session);
        self
    }

    pub fn make_move(mut self, player: AccountHash, move_x: u32, move_y: u32) -> Self {
        let proxy = Code::Hash(self.proxy_contract_hash());
        let args = (self.game_contract_hash(), method::MOVE, move_x, move_y);
        let session = SessionBuilder::new(proxy, args)
            .with_address(player)
            .with_authorization_keys(&[player])
            .build();
        self.context.run(session);
        self
    }

    fn contract_hash(&self, name: &str) -> Hash {
        let key: Key = self
            .context
            .query(PLAYER_X, &[name])
            .unwrap_or_else(|_| panic!("{} contract not found.", name))
            .into_t()
            .unwrap_or_else(|_| panic!("Can't parse to {} to Key.", name));
        key.into_hash()
            .unwrap_or_else(|| panic!("{} is not a type Hash.", name))
    }

    pub fn game_contract_hash(&self) -> Hash {
        self.contract_hash(GAME_CONTRACT_NAME)
    }

    pub fn proxy_contract_hash(&self) -> Hash {
        self.contract_hash(GAME_PROXY_CONTRACT_NAME)
    }

    pub fn player_status(&self, player: AccountHash) -> String {
        let key: String = format!("{}", player);
        self.context
            .query(PLAYER_X, &[GAME_CONTRACT_NAME, key.as_str()])
            .expect("Player status not found")
            .into_t()
            .expect("Cannot parse player status")
    }

    pub fn assert_player_in_game(
        self,
        playing_as: &str,
        player: AccountHash,
        opponent: AccountHash,
    ) -> Self {
        let val = self.player_status(player);
        let expected = format!("playing as {} against {}", playing_as, opponent);
        assert_eq!(val, expected);
        self
    }

    pub fn assert_player_won(self, player: AccountHash, opponent: AccountHash) -> Self {
        let val = self.player_status(player);
        let expected = format!("victorious against {}", opponent);
        assert_eq!(val, expected);
        self
    }

    pub fn assert_player_lost(self, player: AccountHash, opponent: AccountHash) -> Self {
        let val = self.player_status(player);
        let expected = format!("defeated by {}", opponent);
        assert_eq!(val, expected);
        self
    }

    pub fn assert_player_draw(self, player: AccountHash, opponent: AccountHash) -> Self {
        let val = self.player_status(player);
        let expected = format!("draw against {}", opponent);
        assert_eq!(val, expected);
        self
    }

    pub fn assert_board_status(self, pk1: AccountHash, pk2: AccountHash, expected: &str) -> Self {
        let key: String = if pk1 > pk2 {
            format!("Game {} vs {}", pk1, pk2)
        } else {
            format!("Game {} vs {}", pk2, pk1)
        };
        let status: String = self
            .context
            .query(PLAYER_X, &[GAME_CONTRACT_NAME, key.as_str()])
            .expect("Game status not found")
            .into_t()
            .expect("Cannot parse game status");
        assert_eq!(status, expected);
        self
    }
}

#[ignore]
#[test]
fn test_game_with_winner() {
    // This game.
    // X|O|O
    // -+-+-
    // X| |
    // -+-+-
    // X| |

    GameTest::new()
        .deploy()
        .start_game()
        .assert_player_in_game("X", PLAYER_X, PLAYER_O)
        .assert_player_in_game("O", PLAYER_O, PLAYER_X)
        .assert_board_status(PLAYER_X, PLAYER_O, "_________")
        .make_move(PLAYER_X, 0, 0)
        .make_move(PLAYER_O, 0, 1)
        .make_move(PLAYER_X, 1, 0)
        .make_move(PLAYER_O, 0, 2)
        .make_move(PLAYER_X, 2, 0)
        .assert_player_won(PLAYER_X, PLAYER_O)
        .assert_player_lost(PLAYER_O, PLAYER_X)
        .assert_board_status(PLAYER_X, PLAYER_O, "XOOX__X__");
}

#[ignore]
#[test]
fn test_game_with_draw() {
    // This game.
    // X|O|X
    // -+-+-
    // X|O|O
    // -+-+-
    // O|X|X

    GameTest::new()
        .deploy()
        .start_game()
        .make_move(PLAYER_X, 0, 0)
        .make_move(PLAYER_O, 0, 1)
        .make_move(PLAYER_X, 0, 2)
        .make_move(PLAYER_O, 1, 1)
        .make_move(PLAYER_X, 1, 0)
        .make_move(PLAYER_O, 1, 2)
        .make_move(PLAYER_X, 2, 1)
        .make_move(PLAYER_O, 2, 0)
        .make_move(PLAYER_X, 2, 2)
        .assert_player_draw(PLAYER_X, PLAYER_O)
        .assert_player_draw(PLAYER_O, PLAYER_X)
        .assert_board_status(PLAYER_X, PLAYER_O, "XOXXOOOXX");
}

#[ignore]
#[test]
fn test_resign_from_game() {
    GameTest::new()
        .deploy()
        .start_game()
        .make_move(PLAYER_X, 0, 0)
        .concede(PLAYER_O)
        .assert_player_won(PLAYER_X, PLAYER_O)
        .assert_player_lost(PLAYER_O, PLAYER_X);
}
