use std::convert::TryInto;

use engine_shared::{
    account::Account, contract::Contract, motes::Motes, newtypes::CorrelationId,
    stored_value::StoredValue, transform::TypeMismatch,
};
use engine_storage::global_state::StateReader;
use types::{bytesrepr::ToBytes, CLValue, Key, URef, U512};

use crate::{
    execution,
    tracking_copy::{TrackingCopy, TrackingCopyQueryResult},
};

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

    /// Gets a contract by Key
    fn get_contract(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<Contract, Self::Error>;
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
        account_address: [u8; 32],
    ) -> Result<Account, Self::Error> {
        let account_key = Key::Account(account_address);
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
        mint_contract_uref: URef,
        outer_key: Key,
    ) -> Result<Key, Self::Error> {
        let uref = outer_key
            .as_uref()
            .ok_or_else(|| execution::Error::URefNotFound("public purse balance".to_string()))?;
        let local_key_bytes = uref.addr().into_bytes()?;
        let balance_mapping_key = Key::local(mint_contract_uref.addr(), &local_key_bytes);
        match self
            .query(correlation_id, balance_mapping_key, &[])
            .map_err(Into::into)?
        {
            TrackingCopyQueryResult::Success(stored_value) => {
                let cl_value: CLValue = stored_value
                    .try_into()
                    .map_err(execution::Error::TypeMismatch)?;
                Ok(cl_value.into_t()?)
            }
            TrackingCopyQueryResult::ValueNotFound(msg) => Err(execution::Error::URefNotFound(msg)),
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
            TrackingCopyQueryResult::Success(stored_value) => {
                let cl_value: CLValue = stored_value
                    .try_into()
                    .map_err(execution::Error::TypeMismatch)?;
                let balance: U512 = cl_value.into_t()?;
                Ok(Motes::new(balance))
            }
            TrackingCopyQueryResult::ValueNotFound(_) => Err(execution::Error::KeyNotFound(key)),
        }
    }

    fn get_contract(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<Contract, Self::Error> {
        match self
            .get(correlation_id, &key.normalize())
            .map_err(Into::into)?
        {
            Some(StoredValue::Contract(contract)) => Ok(contract),
            Some(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "Contract".to_string(),
                other.type_name(),
            ))),
            None => Err(execution::Error::KeyNotFound(key)),
        }
    }
}
