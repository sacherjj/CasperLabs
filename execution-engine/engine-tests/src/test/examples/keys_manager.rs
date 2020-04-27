use engine_test_support::{
    internal::{
        ExecuteRequestBuilder, InMemoryWasmTestBuilder as TestBuilder, DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::account::{PublicKey, Weight};

const CONTRACT_WASM: &str = "keys_manager.wasm";
const METHOD_SET_KEY_WEIGHT: &str = "set_key_weight";
const METHOD_SET_DEPLOYMENT_THRESHOLD: &str = "set_deployment_threshold";
const METHOD_SET_KEY_MANAGEMENT_THRESHOLD: &str = "set_key_management_threshold";

const ALICE: PublicKey = DEFAULT_ACCOUNT_ADDR;
const BOB: PublicKey = PublicKey::ed25519_from([2u8; 32]);

struct KeysManagerTest {
    pub builder: TestBuilder,
    pub sender: PublicKey,
}

impl KeysManagerTest {
    pub fn new(sender: PublicKey) -> KeysManagerTest {
        let mut builder = TestBuilder::default();
        builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST).commit();
        KeysManagerTest { builder, sender }
    }

    pub fn assert_success_status_and_commit(mut self) -> Self {
        self.builder.expect_success().commit();
        self
    }

    pub fn set_key_weight(mut self, key: PublicKey, weight: Weight) -> Self {
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

    pub fn assert_keys(self, mut expected: Vec<(PublicKey, Weight)>) -> Self {
        // Get and sort existing keys.
        let account = self.builder.get_account(self.sender).unwrap();
        let keys_iter = account.get_associated_keys();
        let mut keys: Vec<(PublicKey, Weight)> = keys_iter.map(|pair| (*pair.0, *pair.1)).collect();
        keys.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap());

        // Sort and parse expected keys.
        expected.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap());

        // Compare keys.
        assert_eq!(keys, expected, "Associated keys don't match.");
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
        .assert_keys(vec![(ALICE, Weight::new(1))])
        .assert_deployment_threshold(1)
        .assert_key_management_threshold(1);
}

#[ignore]
#[test]
fn test_add_key() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(BOB, Weight::new(1))
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, Weight::new(1)), (BOB, Weight::new(1))]);
}

#[ignore]
#[test]
fn test_update_key() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(BOB, Weight::new(1))
        .assert_success_status_and_commit()
        .set_key_weight(BOB, Weight::new(2))
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, Weight::new(1)), (BOB, Weight::new(2))]);
}

#[ignore]
#[test]
fn test_update_self_key() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(ALICE, Weight::new(10))
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, Weight::new(10))]);
}

#[ignore]
#[test]
fn test_remove_key() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(BOB, Weight::new(1))
        .assert_success_status_and_commit()
        .set_key_weight(BOB, Weight::new(0))
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, Weight::new(1))]);
}

#[ignore]
#[test]
fn test_key_management_threshold() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(ALICE, Weight::new(3))
        .assert_success_status_and_commit()
        .set_key_management_threshold(3)
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, Weight::new(3))])
        .assert_key_management_threshold(3);
}

#[ignore]
#[test]
fn test_set_deployment_threshold() {
    KeysManagerTest::new(ALICE)
        .set_key_weight(ALICE, Weight::new(3))
        .assert_success_status_and_commit()
        .set_key_management_threshold(3)
        .assert_success_status_and_commit()
        .set_deployment_threshold(2)
        .assert_success_status_and_commit()
        .assert_keys(vec![(ALICE, Weight::new(3))])
        .assert_key_management_threshold(3)
        .assert_deployment_threshold(2);
}
