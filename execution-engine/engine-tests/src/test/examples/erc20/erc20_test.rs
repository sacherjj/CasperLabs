use std::{convert::TryFrom, rc::Rc};

use engine_core::engine_state::{execution_result::ExecutionResult, CONV_RATE};
use engine_shared::motes::Motes;
use engine_test_support::low_level::{
    utils, ExecuteRequestBuilder, InMemoryWasmTestBuilder as TestBuilder, DEFAULT_GENESIS_CONFIG,
};
use types::{account::PurseId, bytesrepr::ToBytes, CLValue, Key, U512};

const ERC_20_CONTRACT_WASM: &str = "erc20_smart_contract.wasm";
const TRANFER_TO_ACCOUNT_WASM: &str = "transfer_to_account.wasm";
const METHOD_DEPLOY: &str = "deploy";
const METHOD_ASSERT_BALANCE: &str = "assert_balance";
const METHOD_ASSERT_TOTAL_SUPPLY: &str = "assert_total_supply";
const METHOD_ASSERT_ALLOWANCE: &str = "assert_allowance";
const METHOD_TRANSFER: &str = "transfer";
const METHOD_TRANSFER_FROM: &str = "transfer_from";
const METHOD_APPROVE: &str = "approve";
const METHOD_BUY_PROXY: &str = "buy_proxy";
const METHOD_SELL_PROXY: &str = "sell_proxy";
const UREF_NAME_ERC20_PROXY: &str = "erc20_proxy";
const TOKEN_NAME: &str = "token";
const TOKEN_PURSE_NAME: &str = "erc20_main_purse";

pub struct ERC20Test {
    pub builder: TestBuilder,
    pub token_hash: Option<[u8; 32]>,
    pub proxy_hash: Option<[u8; 32]>,
}

impl ERC20Test {
    pub fn new(sender: [u8; 32], init_balance: U512) -> ERC20Test {
        let mut builder = TestBuilder::default();
        builder.run_genesis(&DEFAULT_GENESIS_CONFIG).commit();
        let test = ERC20Test {
            builder,
            token_hash: None,
            proxy_hash: None,
        };
        test.deploy_erc20_contract(sender, init_balance)
            .assert_success_status_and_commit()
            .with_contract(sender, TOKEN_NAME)
    }

    pub fn query_contract_hash(&self, account: [u8; 32], name: &str) -> [u8; 32] {
        let account_key = Key::Account(account);
        let value: CLValue = self
            .builder
            .query(None, account_key, &[name])
            .and_then(|v| CLValue::try_from(v).ok())
            .expect("should have named uref.");
        let key: Key = value.into_t().unwrap();
        key.as_hash().unwrap()
    }

    pub fn deploy_erc20_contract(mut self, sender: [u8; 32], init_balance: U512) -> Self {
        let request = ExecuteRequestBuilder::standard(
            sender,
            ERC_20_CONTRACT_WASM,
            (METHOD_DEPLOY, TOKEN_NAME, init_balance),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_erc20_transfer(
        mut self,
        sender: [u8; 32],
        recipient: [u8; 32],
        amount: U512,
    ) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (self.get_token_hash(), METHOD_TRANSFER, recipient, amount),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_erc20_balance_assertion(mut self, sender: [u8; 32], expected_amount: U512) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (
                self.get_token_hash(),
                METHOD_ASSERT_BALANCE,
                sender,
                expected_amount,
            ),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_erc20_allowance_assertion(
        mut self,
        owner: [u8; 32],
        spender: [u8; 32],
        expected_amount: U512,
    ) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            owner,
            self.get_proxy_hash(),
            (
                self.get_token_hash(),
                METHOD_ASSERT_ALLOWANCE,
                owner,
                spender,
                expected_amount,
            ),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_erc20_total_supply_assertion(
        mut self,
        sender: [u8; 32],
        expected_amount: U512,
    ) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (
                self.get_token_hash(),
                METHOD_ASSERT_TOTAL_SUPPLY,
                expected_amount,
            ),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_erc20_approve(
        mut self,
        sender: [u8; 32],
        recipient: [u8; 32],
        amount: U512,
    ) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (self.get_token_hash(), METHOD_APPROVE, recipient, amount),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_erc20_transfer_from(
        mut self,
        sender: [u8; 32],
        owner: [u8; 32],
        recipient: [u8; 32],
        amount: U512,
    ) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (
                self.get_token_hash(),
                METHOD_TRANSFER_FROM,
                owner,
                recipient,
                amount,
            ),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_erc20_buy(mut self, sender: [u8; 32], amount: U512) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (self.get_token_hash(), METHOD_BUY_PROXY, amount),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_erc20_sell(mut self, sender: [u8; 32], amount: U512) -> Self {
        let request = ExecuteRequestBuilder::contract_call_by_hash(
            sender,
            self.get_proxy_hash(),
            (self.get_token_hash(), METHOD_SELL_PROXY, amount),
        )
        .build();
        self.builder.exec(request);
        self
    }

    pub fn call_clx_transfer_with_success(
        mut self,
        sender: [u8; 32],
        recipient: [u8; 32],
        amount: U512,
    ) -> Self {
        let request = ExecuteRequestBuilder::standard(
            sender,
            TRANFER_TO_ACCOUNT_WASM,
            (recipient, amount.as_u64()),
        )
        .build();
        self.builder.exec(request).expect_success().commit();
        self
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
        let expected_message = format!("Revert({:?})", code);
        assert!(deploy_error.contains(&expected_message));
        self
    }

    pub fn assert_clx_account_balance_no_gas(self, account: [u8; 32], expected: U512) -> Self {
        let account_purse = self.builder.get_account(account).unwrap().purse_id();
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

    pub fn assert_clx_contract_balance(self, expected: U512) -> Self {
        let token_contract_value = self
            .builder
            .query(None, Key::Hash(self.get_token_hash()), &[])
            .expect("should have token contract.");

        let purse_uref = token_contract_value
            .as_contract()
            .unwrap()
            .named_keys()
            .get(TOKEN_PURSE_NAME)
            .unwrap()
            .as_uref()
            .unwrap();
        let contract_balance = self.builder.get_purse_balance(PurseId::new(*purse_uref));
        assert_eq!(contract_balance, expected);
        self
    }

    /// Balances are stored in the local storage and are represented as 33 bytes arrays where:
    /// - the first byte is "1";
    /// - the rest is 32 bytes of the account's public key.
    pub fn assert_erc20_balance(self, address: [u8; 32], expected: U512) -> Self {
        let mut balance_bytes: Vec<u8> = Vec::with_capacity(33);
        balance_bytes.extend(&[1]);
        balance_bytes.extend(&address);
        let balance_key = Key::local(self.get_token_hash(), &balance_bytes.to_bytes().unwrap());
        let value: CLValue = self
            .builder
            .query(None, balance_key.clone(), &[])
            .and_then(|v| CLValue::try_from(v).ok())
            .expect("should have local value.");
        let balance: U512 = value.into_t().unwrap();
        assert_eq!(
            balance, expected,
            "Balance assertion failure for {:?}",
            address
        );
        self
    }

    /// Total supply value is stored in the local storage under the [255u8; 32] key.
    pub fn assert_erc20_total_supply(self, expected: U512) -> Self {
        let total_supply_key = Key::local(self.get_token_hash(), &[255u8; 32]);
        let value: CLValue = self
            .builder
            .query(None, total_supply_key.clone(), &[])
            .and_then(|v| CLValue::try_from(v).ok())
            .expect("should have total supply key.");
        let total_supply: U512 = value.into_t().unwrap();
        assert_eq!(total_supply, expected, "Total supply assertion failure.");
        self
    }

    /// Allowances are stored in the local storage and are represented are 64 bytes arrays where:
    /// - the first 32 bytes are token owner's public key;
    /// - the second 32 bytes are token spender's public key.
    pub fn assert_erc20_allowance(
        self,
        owner: [u8; 32],
        spender: [u8; 32],
        expected: U512,
    ) -> Self {
        let allowance_bytes: Vec<u8> = owner.iter().chain(spender.iter()).copied().collect();
        let allowance_key = Key::local(self.get_token_hash(), &allowance_bytes.to_bytes().unwrap());
        let value: CLValue = self
            .builder
            .query(None, allowance_key.clone(), &[])
            .and_then(|v| CLValue::try_from(v).ok())
            .expect("should have allowance key.");
        let allowance: U512 = value.into_t().unwrap();
        assert_eq!(
            allowance, expected,
            "Allowance assertion failure for {:?}",
            owner
        );
        self
    }

    pub fn get_token_hash(&self) -> [u8; 32] {
        self.token_hash
            .unwrap_or_else(|| panic!("Field token_hash not set."))
    }

    pub fn get_proxy_hash(&self) -> [u8; 32] {
        self.proxy_hash
            .unwrap_or_else(|| panic!("Field proxy_hash not set."))
    }

    pub fn with_contract(mut self, sender: [u8; 32], token_name: &str) -> Self {
        self.token_hash = Some(self.query_contract_hash(sender, token_name));
        self.proxy_hash = Some(self.query_contract_hash(sender, UREF_NAME_ERC20_PROXY));
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
