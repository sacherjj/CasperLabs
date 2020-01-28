use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder as TestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG,
};
use types::account::{PublicKey, Weight};

const CONTRACT_WASM: &str = "keys_manager.wasm";
const METHOD_SET_KEY_WEIGHT: &str = "set_key_weight";
const METHOD_SET_DEPLOYMENT_THRESHOLD: &str = "set_deployment_threshold";
const METHOD_SET_KEY_MANAGEMENT_THRESHOLD: &str = "set_key_management_threshold";

const ALICE: [u8; 32] = DEFAULT_ACCOUNT_ADDR;
const BOB: [u8; 32] = [2u8; 32];

struct KeysManagerTest {
    pub builder: TestBuilder,
    pub sender: [u8; 32],
}

impl KeysManagerTest {
    pub fn new(sender: [u8; 32]) -> KeysManagerTest {
        let mut builder = TestBuilder::default();
        builder.run_genesis(&DEFAULT_GENESIS_CONFIG).commit();
        KeysManagerTest { builder, sender }
    }

    pub fn assert_success_status_and_commit(mut self) -> Self {
        self.builder.expect_success().commit();
        self
    }

    pub fn set_key_weight(mut self, key: [u8; 32], weight: u8) -> Self {
        let request = ExecuteRequestBuilder::standard(
            self.sender,
            CONTRACT_WASM,
            (METHOD_SET_KEY_WEIGHT, key, weight),
        )
        .build();
        self.builder.exec(request);
        self
    }

    fn update_threshold(mut self, method: &str, threshold: u8) -> Self {
        let request =
            ExecuteRequestBuilder::standard(self.sender, CONTRACT_WASM, (method, threshold))
                .build();
        self.builder.exec(request);
        self
    }

    pub fn set_deployment_threshold(self, threshold: u8) -> Self {
        self.update_threshold(METHOD_SET_DEPLOYMENT_THRESHOLD, threshold)
    }

    pub fn set_key_management_threshold(self, threshold: u8) -> Self {
        self.update_threshold(METHOD_SET_KEY_MANAGEMENT_THRESHOLD, threshold)
    }

    pub fn assert_keys(self, expected: Vec<([u8; 32], u8)>) -> Self {
        // Get and sort existing keys.
        let account = self.builder.get_account(self.sender).unwrap();
        let keys_iter = account.get_associated_keys();
        let mut keys: Vec<(PublicKey, Weight)> = keys_iter.map(|pair| (*pair.0, *pair.1)).collect();
        keys.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap());

        // Sort and parse expected keys.
        let mut expected_parsed: Vec<(PublicKey, Weight)> = expected
            .iter()
            .map(|(key, weight)| (PublicKey::new(*key), Weight::new(*weight)))
            .collect();
        expected_parsed.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap());

        // Compare keys.
        assert_eq!(keys, expected_parsed, "Associated keys don't match.");
        self
    }

    pub fn assert_deployment_threshold(self, expected: u8) -> Self {
        let account = self.builder.get_account(self.sender).unwrap();
        let threshold = *account.action_thresholds().deployment();
        assert_eq!(
            Weight::new(expected),
            threshold,
            "Deployment threshold doesn't match."
        );
        self
    }

    pub fn assert_key_management_threshold(self, expected: u8) -> Self {
        let account = self.builder.get_account(self.sender).unwrap();
        let threshold = *account.action_thresholds().key_management();
        assert_eq!(
            Weight::new(expected),
            threshold,
            "Key Management threshold doesn't match."
        );
        self
    }
}

#[ignore]
#[test]
fn test_init_setup() {
    KeysManagerTest::new(ALICE)
        .assert_keys(vec![(ALICE, 1)])
        .assert_deployment_threshold(1)
        .assert_key_management_threshold(1);
}

#[ignore]
#[test]
fn test_add_key() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(BOB, 1)
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, 1), (BOB, 1)]);
}

#[ignore]
#[test]
fn test_update_key() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(BOB, 1)
        .assert_success_status_and_commit()
        .set_key_weight(BOB, 2)
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, 1), (BOB, 2)]);
}

#[ignore]
#[test]
fn test_update_self_key() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(ALICE, 10)
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, 10)]);
}

#[ignore]
#[test]
fn test_remove_key() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(BOB, 1)
        .assert_success_status_and_commit()
        .set_key_weight(BOB, 0)
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, 1)]);
}

#[ignore]
#[test]
fn test_key_management_threshold() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(ALICE, 3)
        .assert_success_status_and_commit()
        .set_key_management_threshold(3)
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, 3)])
        .assert_key_management_threshold(3);
}

#[ignore]
#[test]
fn test_set_deployment_threshold() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(ALICE, 3)
        .assert_success_status_and_commit()
        .set_key_management_threshold(3)
        .assert_success_status_and_commit()
        .set_deployment_threshold(2)
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, 3)])
        .assert_key_management_threshold(3)
        .assert_deployment_threshold(2);
}
