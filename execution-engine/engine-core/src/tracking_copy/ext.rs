use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::uref::URef;
use contract_ffi::value::{Account, Contract, Value};
use engine_shared::motes::Motes;
use engine_shared::newtypes::CorrelationId;
use engine_shared::transform::TypeMismatch;
use engine_storage::global_state::StateReader;

use crate::execution;
use crate::tracking_copy::{QueryResult, TrackingCopy};

pub struct SystemContractInfo {
    key: Key,
    contract: Contract,
}

impl SystemContractInfo {
    pub fn key(&self) -> Key {
        self.key
    }

    pub fn contract(&self) -> &Contract {
        &self.contract
    }

    pub fn module_bytes(&self) -> Vec<u8> {
        self.contract.bytes().to_vec()
    }
}

impl SystemContractInfo {
    fn new(key: Key, contract: Contract) -> SystemContractInfo {
        SystemContractInfo { key, contract }
    }
}

pub trait TrackingCopyExt<R> {
    type Error;

    /// Gets the account at a given account address.
    fn get_account(
        &mut self,
        correlation_id: CorrelationId,
        account_address: [u8; 32],
    ) -> Result<Account, Self::Error>;

    /// Gets the purse balance key for a given purse id
    fn get_purse_balance_key(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract_uref: URef,
        purse: Key,
    ) -> Result<Key, Self::Error>;

    /// Gets the balance at a given balance key
    fn get_purse_balance(
        &mut self,
        correlation_id: CorrelationId,
        balance_key: Key,
    ) -> Result<Motes, Self::Error>;

    /// Gets the system contract, packaged with its outer uref key and inner
    /// uref key
    fn get_system_contract_info(
        &mut self,
        correlation_id: CorrelationId,
        outer_key: Key,
    ) -> Result<SystemContractInfo, Self::Error>;

    /// Gets a contract by Key
    fn get_contract(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<Contract, Self::Error>;
}

impl<R: StateReader<Key, Value>> TrackingCopyExt<R> for TrackingCopy<R>
where
    R::Error: Into<execution::Error>,
{
    type Error = execution::Error;

    fn get_account(
        &mut self,
        correlation_id: CorrelationId,
        account_address: [u8; 32],
    ) -> Result<Account, Self::Error> {
        let account_key = Key::Account(account_address);
        match self.get(correlation_id, &account_key).map_err(Into::into)? {
            Some(Value::Account(account)) => Ok(account),
            Some(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "Account".to_string(),
                other.type_string(),
            ))),
            None => Err(execution::Error::KeyNotFound(account_key)),
        }
    }

    fn get_purse_balance_key(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract_uref: URef,
        outer_key: Key,
    ) -> Result<Key, Self::Error> {
        let uref = outer_key
            .as_uref()
            .ok_or_else(|| execution::Error::URefNotFound("public purse balance".to_string()))?;
        let local_key_bytes = uref.addr().to_bytes()?;
        let balance_mapping_key = Key::local(mint_contract_uref.addr(), &local_key_bytes);
        match self
            .query(correlation_id, balance_mapping_key, &[])
            .map_err(Into::into)?
        {
            QueryResult::Success(Value::Key(key)) => Ok(key),
            QueryResult::Success(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "Value::Key".to_string(),
                other.type_string(),
            ))),
            QueryResult::ValueNotFound(msg) => Err(execution::Error::URefNotFound(msg)),
        }
    }

    fn get_purse_balance(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<Motes, Self::Error> {
        let query_result = match self.query(correlation_id, key, &[]) {
            Ok(query_result) => query_result,
            Err(_) => return Err(execution::Error::KeyNotFound(key)),
        };
        match query_result {
            QueryResult::Success(Value::UInt512(balance)) => Ok(Motes::new(balance)),
            QueryResult::Success(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "Value::UInt512".to_string(),
                other.type_string(),
            ))),
            QueryResult::ValueNotFound(_) => Err(execution::Error::KeyNotFound(key)),
        }
    }

    fn get_system_contract_info(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<SystemContractInfo, Self::Error> {
        let contract = self.get_contract(correlation_id, key)?;
        Ok(SystemContractInfo::new(key, contract))
    }

    fn get_contract(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<Contract, Self::Error> {
        let contract = match self.get(correlation_id, &key).map_err(Into::into)? {
            Some(Value::Contract(contract)) => contract,
            Some(other) => {
                return Err(execution::Error::TypeMismatch(TypeMismatch::new(
                    "Value::Contract".to_string(),
                    other.type_string(),
                )))
            }
            None => return Err(execution::Error::KeyNotFound(key)),
        };
        Ok(contract)
    }
}
