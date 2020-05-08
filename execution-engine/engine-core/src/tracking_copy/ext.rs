use std::convert::TryInto;

use engine_shared::{
    account::Account, contract::ContractWasm, motes::Motes, newtypes::CorrelationId,
    stored_value::StoredValue, TypeMismatch,
};
use engine_storage::global_state::StateReader;
use types::{
    account::PublicKey, bytesrepr::ToBytes, CLValue, Contract, ContractHash, ContractPackage, Key,
    SemVer, U512,
};

use crate::{execution, tracking_copy::TrackingCopy};

pub trait TrackingCopyExt<R> {
    type Error;

    /// Gets the account at a given account address.
    fn get_account(
        &mut self,
        correlation_id: CorrelationId,
        public_key: PublicKey,
    ) -> Result<Account, Self::Error>;

    /// Gets the purse balance key for a given purse id
    fn get_purse_balance_key(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract: ContractHash,
        purse: Key,
    ) -> Result<Key, Self::Error>;

    /// Gets the balance at a given balance key
    fn get_purse_balance(
        &mut self,
        correlation_id: CorrelationId,
        balance_key: Key,
    ) -> Result<Motes, Self::Error>;

    /// Gets a contract by Key
    fn get_contract_wasm(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<ContractWasm, Self::Error>;

    /// Gets a contract header by Key
    fn get_contract(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<Contract, Self::Error>;

    /// Gets a contract metadata by Key
    fn get_contract_package(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<ContractPackage, Self::Error>;
}

impl<R> TrackingCopyExt<R> for TrackingCopy<R>
where
    R: StateReader<Key, StoredValue>,
    R::Error: Into<execution::Error>,
{
    type Error = execution::Error;

    fn get_account(
        &mut self,
        correlation_id: CorrelationId,
        public_key: PublicKey,
    ) -> Result<Account, Self::Error> {
        let account_key = Key::Account(public_key);
        match self.get(correlation_id, &account_key).map_err(Into::into)? {
            Some(StoredValue::Account(account)) => Ok(account),
            Some(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "Account".to_string(),
                other.type_name(),
            ))),
            None => Err(execution::Error::KeyNotFound(account_key)),
        }
    }

    fn get_purse_balance_key(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract: ContractHash,
        outer_key: Key,
    ) -> Result<Key, Self::Error> {
        let uref = outer_key
            .as_uref()
            .ok_or_else(|| execution::Error::URefNotFound("public purse balance".to_string()))?;
        let local_key_bytes = uref.addr().into_bytes()?;
        let balance_mapping_key = Key::local(mint_contract, &local_key_bytes);
        match self
            .read(correlation_id, &balance_mapping_key)
            .map_err(Into::into)?
        {
            Some(stored_value) => {
                let cl_value: CLValue = stored_value
                    .try_into()
                    .map_err(execution::Error::TypeMismatch)?;
                Ok(cl_value.into_t()?)
            }
            None => Err(execution::Error::URefNotFound(
                "public purse balance".to_string(),
            )),
        }
    }

    fn get_purse_balance(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<Motes, Self::Error> {
        let read_result = match self.read(correlation_id, &key) {
            Ok(read_result) => read_result,
            Err(_) => return Err(execution::Error::KeyNotFound(key)),
        };
        match read_result {
            Some(stored_value) => {
                let cl_value: CLValue = stored_value
                    .try_into()
                    .map_err(execution::Error::TypeMismatch)?;
                let balance: U512 = cl_value.into_t()?;
                Ok(Motes::new(balance))
            }
            None => Err(execution::Error::KeyNotFound(key)),
        }
    }

    fn get_contract_wasm(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<ContractWasm, Self::Error> {
        match self
            .get(correlation_id, &key.normalize())
            .map_err(Into::into)?
        {
            Some(StoredValue::ContractMetadata(metadata)) => {
                todo!("get_contract with ContractMetadata");
                // let contract_header = metadata
                //     .get_version(&SemVer::V1_0_0)
                //     .ok_or_else(|| execution::Error::KeyNotFound(key))?;
                // self.get_contract(correlation_id, contract_header.contract_key())
            }
            Some(StoredValue::ContractWasm(contract_wasm)) => Ok(contract_wasm),
            Some(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "Contract".to_string(),
                other.type_name(),
            ))),
            None => Err(execution::Error::KeyNotFound(key)),
        }
    }

    /// Gets a contract header by Key
    fn get_contract(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<Contract, Self::Error> {
        match self
            .get(correlation_id, &key.normalize())
            .map_err(Into::into)?
        {
            Some(StoredValue::Contract(contract_header)) => Ok(contract_header),
            Some(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "ContractHeader".to_string(),
                other.type_name(),
            ))),
            None => Err(execution::Error::KeyNotFound(key)),
        }
    }

    fn get_contract_package(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<ContractPackage, Self::Error> {
        match self
            .get(correlation_id, &key.normalize())
            .map_err(Into::into)?
        {
            Some(StoredValue::ContractMetadata(contract_metadata)) => Ok(contract_metadata),
            Some(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "ContractMetadata".to_string(),
                other.type_name(),
            ))),
            None => Err(execution::Error::KeyNotFound(key)),
        }
    }
}
