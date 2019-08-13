use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::uref::URef;
use contract_ffi::value::{Account, Contract, Value, U512};

use engine_shared::newtypes::{CorrelationId, Validated};
use engine_shared::transform::TypeMismatch;
use engine_storage::global_state::StateReader;
use execution;
use tracking_copy::{QueryResult, TrackingCopy};

pub struct SystemContractInfo {
    public_key: Key,
    inner_uref_key: Key,
    contract: Contract,
}

impl SystemContractInfo {
    pub fn public_key(&self) -> Key {
        self.public_key
    }

    pub fn inner_uref_key(&self) -> Key {
        self.inner_uref_key
    }

    pub fn contract(&self) -> &Contract {
        &self.contract
    }

    pub fn module_bytes(&self) -> Vec<u8> {
        self.contract.bytes().to_vec()
    }
}

impl SystemContractInfo {
    fn new(public_key: Key, inner_uref_key: Key, contract: Contract) -> SystemContractInfo {
        SystemContractInfo {
            public_key,
            inner_uref_key,
            contract,
        }
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

    fn get_purse_balance_key(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract_uref: URef,
        purse: Key,
    ) -> Result<Key, Self::Error>;

    fn get_purse_balance(
        &mut self,
        correlation_id: CorrelationId,
        balance_key: Key,
    ) -> Result<U512, Self::Error>;

    fn get_system_contract_info(
        &mut self,
        correlation_id: CorrelationId,
        pos_public_uref: Key,
    ) -> Result<SystemContractInfo, Self::Error>;

    fn handle_nonce(&mut self, account: &mut Account, nonce: u64) -> Result<(), Self::Error>;
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
    ) -> Result<U512, Self::Error> {
        let query_result = match self.query(correlation_id, key, &[]) {
            Ok(query_result) => query_result,
            Err(_) => return Err(execution::Error::KeyNotFound(key)),
        };
        match query_result {
            QueryResult::Success(Value::UInt512(balance)) => Ok(balance),
            QueryResult::Success(other) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "Value::UInt512".to_string(),
                other.type_string(),
            ))),
            QueryResult::ValueNotFound(_) => Err(execution::Error::KeyNotFound(key)),
        }
    }

    // get urefs, pointer, and bytes for a system contract
    fn get_system_contract_info(
        &mut self,
        correlation_id: CorrelationId,
        public_key: Key,
    ) -> Result<SystemContractInfo, Self::Error> {
        let inner_uref_key = match self.get(correlation_id, &public_key).map_err(Into::into)? {
            Some(Value::Key(key @ Key::URef(_))) => key.normalize(),
            // This match needs to be improved
            Some(other) => {
                return Err(execution::Error::TypeMismatch(TypeMismatch::new(
                    "Value::Key".to_string(),
                    other.type_string(),
                )))
            }
            None => return Err(execution::Error::KeyNotFound(public_key)),
        };

        let contract = match self
            .get(correlation_id, &inner_uref_key)
            .map_err(Into::into)?
        {
            Some(Value::Contract(contract)) => contract,
            Some(other) => {
                return Err(execution::Error::TypeMismatch(TypeMismatch::new(
                    "Value::Contract".to_string(),
                    other.type_string(),
                )))
            }
            None => return Err(execution::Error::KeyNotFound(inner_uref_key)),
        };

        Ok(SystemContractInfo::new(
            public_key,
            inner_uref_key,
            contract,
        ))
    }

    fn handle_nonce(&mut self, account: &mut Account, nonce: u64) -> Result<(), Self::Error> {
        if nonce.checked_sub(account.nonce()).unwrap_or(0) != 1 {
            return Err(execution::Error::InvalidNonce {
                deploy_nonce: nonce,
                expected_nonce: account.nonce() + 1,
            });
        } else {
            account.increment_nonce();
        }

        let validated_key: Validated<Key> =
            Validated::new::<!, _>(Key::Account(account.pub_key()), Validated::valid).unwrap();

        // Store updated account with new nonce
        self.write(
            validated_key,
            Validated::new(account.clone().into(), Validated::valid).unwrap(),
        );

        Ok(())
    }
}
