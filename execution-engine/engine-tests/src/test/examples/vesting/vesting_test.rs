use std::{convert::TryFrom, rc::Rc};

use engine_core::engine_state::{execution_result::ExecutionResult, CONV_RATE};
use engine_shared::motes::Motes;
use engine_test_support::internal::{
    utils, ExecuteRequestBuilder, InMemoryWasmTestBuilder as TestBuilder,
    DEFAULT_RUN_GENESIS_REQUEST,
};
use types::{account::PublicKey, bytesrepr::FromBytes, ApiError, CLTyped, CLValue, Key, U512};

const TRANFER_TO_ACCOUNT_WASM: &str = "transfer_to_account_u512.wasm";
const VESTING_CONTRACT_WASM: &str = "vesting_smart_contract.wasm";
const VESTING_PROXY_CONTRACT_NAME: &str = "vesting_proxy";
const VESTING_CONTRACT_NAME: &str = "vesting_01";

mod method {
    pub const DEPLOY: &str = "deploy";
    pub const PAUSE: &str = "pause";
    pub const UNPAUSE: &str = "unpause";
    pub const WITHDRAW_PROXY: &str = "withdraw_proxy";
    pub const ADMIN_RELEASE_PROXY: &str = "admin_release_proxy";
}

pub mod key {
    pub const CLIFF_TIMESTAMP: &str = "cliff_timestamp";
    pub const CLIFF_AMOUNT: &str = "cliff_amount";
    pub const DRIP_DURATION: &str = "drip_duration";
    pub const DRIP_AMOUNT: &str = "drip_amount";
    pub const TOTAL_AMOUNT: &str = "total_amount";
    pub const RELEASED_AMOUNT: &str = "released_amount";
    pub const ADMIN_RELEASE_DURATION: &str = "admin_release_duration";
    pub const PAUSE_FLAG: &str = "is_paused";
    pub const ON_PAUSE_DURATION: &str = "on_pause_duration";
    pub const LAST_PAUSE_TIMESTAMP: &str = "last_pause_timestamp";
    pub const PURSE_NAME: &str = "vesting_main_purse";
}

pub struct VestingConfig {
    pub cliff_timestamp: U512,
    pub cliff_amount: U512,
    pub drip_duration: U512,
    pub drip_amount: U512,
    pub total_amount: U512,
    pub admin_release_duration: U512,
}

impl Default for VestingConfig {
    fn default() -> VestingConfig {
        VestingConfig {
            cliff_timestamp: 10.into(),
            cliff_amount: 2.into(),
            drip_duration: 3.into(),
            drip_amount: 5.into(),
            total_amount: 1000.into(),
            admin_release_duration: 123.into(),
        }
    }
}

pub struct VestingTest {
    pub builder: TestBuilder,
    pub vesting_hash: Option<[u8; 32]>,
    pub proxy_hash: Option<[u8; 32]>,
    pub current_timestamp: u64,
}

impl VestingTest {
    pub fn new(
        sender: PublicKey,
        admin: PublicKey,
        recipient: PublicKey,
        vesting_config: &VestingConfig,
    ) -> VestingTest {
        let mut builder = TestBuilder::default();
        builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST).commit();
        let test = VestingTest {
            builder,
            vesting_hash: None,
            proxy_hash: None,
            current_timestamp: 0,
        };
        test.deploy_vesting_contract(sender, admin, recipient, vesting_config)
            .assert_success_status_and_commit()
            .with_contract(sender, VESTING_CONTRACT_NAME)
    }

    pub fn assert_success_status_and_commit(mut self) -> Self {
        self.builder.expect_success().commit();
        self
    }

    pub fn assert_failure_with_exit_code(self, code: u32) -> Self {
        let last_deploy_index = self.builder.get_exec_responses_count();
        let deploy_error = self
            .builder
            .exec_error_message(last_deploy_index - 1)
            .unwrap();
        let expected_message = format!("{:?}", ApiError::from(code));
        assert!(deploy_error.contains(&expected_message));
        self
    }

    pub fn with_contract(mut self, sender: PublicKey, vesting_contract_name: &str) -> Self {
        self.vesting_hash = Some(self.query_contract_hash(sender, vesting_contract_name));
        self.proxy_hash = Some(self.query_contract_hash(sender, VESTING_PROXY_CONTRACT_NAME));
        self
    }

    pub fn with_block_timestamp(mut self, time: u64) -> Self {
        self.current_timestamp = time;
        self
    }

    pub fn get_vesting_hash(&self) -> [u8; 32] {
        self.vesting_hash
            .unwrap_or_else(|| panic!("Field vesting_hash not set."))
    }

    pub fn get_proxy_hash(&self) -> [u8; 32] {
        self.proxy_hash
            .unwrap_or_else(|| panic!("Field proxy_hash not set."))
    }

    pub fn query_contract_hash(&self, account: PublicKey, name: &str) -> [u8; 32] {
        let account_key = Key::Account(account);
        let value: CLValue = self
            .builder
            .query(None, account_key, &[name])
            .and_then(|v| CLValue::try_from(v).map_err(|error| format!("{:?}", error)))
            .expect("should have named uref in the account space.");
        let key: Key = value.into_t().unwrap();
        key.into_hash().unwrap()
    }

    pub fn deploy_vesting_contract(
        mut self,
        sender: PublicKey,
        admin: PublicKey,
        recipient: PublicKey,
        vesting_config: &VestingConfig,
    ) -> Self {
        let request = ExecuteRequestBuilder::standard(
            sender,
            VESTING_CONTRACT_WASM,
            (
                method::DEPLOY,
                VESTING_CONTRACT_NAME,
                admin,
                recipient,
                vesting_config.cliff_timestamp,
                vesting_config.cliff_amount,
                vesting_config.drip_duration,
                vesting_config.drip_amount,
                vesting_config.total_amount,
                vesting_config.admin_release_duration,
            ),
        )
        .with_block_time(self.current_timestamp)
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_vesting_pause(mut self, sender: PublicKey) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (self.get_vesting_hash(), method::PAUSE),
        )
        .with_block_time(self.current_timestamp)
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_vesting_unpause(mut self, sender: PublicKey) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (self.get_vesting_hash(), method::UNPAUSE),
        )
        .with_block_time(self.current_timestamp)
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_withdraw(mut self, sender: PublicKey, amount: U512) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (self.get_vesting_hash(), method::WITHDRAW_PROXY, amount),
        )
        .with_block_time(self.current_timestamp)
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_admin_release(mut self, sender: PublicKey) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (self.get_vesting_hash(), method::ADMIN_RELEASE_PROXY),
        )
        .with_block_time(self.current_timestamp)
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_clx_transfer_with_success(
        mut self,
        sender: PublicKey,
        recipient: PublicKey,
        amount: U512,
    ) -> Self {
        let request =
            ExecuteRequestBuilder::standard(sender, TRANFER_TO_ACCOUNT_WASM, (recipient, amount))
                .with_block_time(self.current_timestamp)
                .build();
        self.builder.exec(request).expect_success().commit();
        self
    }

    pub fn assert_clx_vesting_balance(self, expected: &U512) -> Self {
        let token_contract_value = self
            .builder
            .query(None, Key::Hash(self.get_vesting_hash()), &[])
            .expect("should have token contract.");

        let purse = token_contract_value
            .as_contract()
            .unwrap()
            .named_keys()
            .get(key::PURSE_NAME)
            .unwrap()
            .into_uref()
            .unwrap();
        let contract_balance = self.builder.get_purse_balance(purse);
        assert_eq!(&contract_balance, expected);
        self
    }

    pub fn assert_clx_account_balance_no_gas(self, account: PublicKey, expected: U512) -> Self {
        let account_purse = self.builder.get_account(account).unwrap().main_purse();
        let mut account_balance = self.builder.get_purse_balance(account_purse);
        let last_deploy_index = self.builder.get_exec_responses_count();
        let execution_costs = (0..last_deploy_index)
            .map(|i| self.builder.get_exec_response(i).unwrap())
            .map(|response| get_cost(response))
            .fold(U512::zero(), |sum, cost| sum + cost);
        account_balance += execution_costs;
        assert_eq!(account_balance, expected);
        self
    }

    fn get_value<T: FromBytes + CLTyped>(&self, name: &str) -> T {
        let contract = self
            .builder
            .query(None, Key::Hash(self.get_vesting_hash()), &[])
            .expect("should have token contract.");

        let key_uref: &Key = contract
            .as_contract()
            .unwrap()
            .named_keys()
            .get(name)
            .unwrap();

        let value = self
            .builder
            .query(None, *key_uref, &[])
            .and_then(|v| CLValue::try_from(v).map_err(|error| format!("{:?}", error)))
            .unwrap_or_else(|error| {
                panic!("should have local value for {} key - {:?}", name, error)
            });

        value.into_t().unwrap()
    }

    pub fn assert_cliff_timestamp(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::CLIFF_TIMESTAMP);
        assert_eq!(&value, expected, "cliff_timestamp assertion failure");
        self
    }

    pub fn assert_cliff_amount(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::CLIFF_AMOUNT);
        assert_eq!(&value, expected, "cliff_amount assertion failure");
        self
    }

    pub fn assert_drip_duration(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::DRIP_DURATION);
        assert_eq!(&value, expected, "drip_duration assertion failure");
        self
    }

    pub fn assert_drip_amount(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::DRIP_AMOUNT);
        assert_eq!(&value, expected, "drip_amount assertion failure");
        self
    }

    pub fn assert_total_amount(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::TOTAL_AMOUNT);
        assert_eq!(&value, expected, "total_amount assertion failure");
        self
    }

    pub fn assert_released_amount(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::RELEASED_AMOUNT);
        assert_eq!(&value, expected, "released_amount assertion failure");
        self
    }

    pub fn assert_admin_release_duration(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::ADMIN_RELEASE_DURATION);
        assert_eq!(&value, expected, "admin_release_duration assertion failure");
        self
    }

    pub fn assert_on_pause_duration(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::ON_PAUSE_DURATION);
        assert_eq!(&value, expected, "on_pause_duration assertion failure");
        self
    }

    pub fn assert_last_pause_timestamp(self, expected: &U512) -> Self {
        let value: U512 = self.get_value(key::LAST_PAUSE_TIMESTAMP);
        assert_eq!(&value, expected, "last_pause_timestamp assertion failure");
        self
    }

    pub fn assert_paused(self) -> Self {
        let value: bool = self.get_value(key::PAUSE_FLAG);
        assert!(value, "contract is not paused");
        self
    }

    pub fn assert_unpaused(self) -> Self {
        let value: bool = self.get_value(key::PAUSE_FLAG);
        assert!(!value, "contract is not paused");
        self
    }
}

fn get_cost(response: &[Rc<ExecutionResult>]) -> U512 {
    let motes = Motes::from_gas(
        utils::get_exec_costs(response)
            .into_iter()
            .fold(Default::default(), |i, acc| i + acc),
        CONV_RATE,
    )
    .expect("should convert");
    motes.value()
}
