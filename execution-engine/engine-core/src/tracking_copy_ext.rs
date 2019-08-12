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
    pub public_key: Key,
    pub inner_uref: URef,
    pub inner_uref_key: Key,
    pub contract: Contract,
    pub module_bytes: Vec<u8>,
}

impl SystemContractInfo {
    fn new(
        public_key: Key,
        inner_uref: URef,
        inner_uref_key: Key,
        contract: Contract,
        module_bytes: Vec<u8>,
    ) -> SystemContractInfo {
        SystemContractInfo {
            public_key,
            inner_uref,
            inner_uref_key,
            contract,
            module_bytes,
        }
    }
}

pub trait TrackingCopyExt<R> {
    type Error;

    fn get_account(
        &mut self,
        correlation_id: CorrelationId,
        account_address: [u8; 32],
    ) -> Result<Account, Self::Error>;

    fn query_value_key(
        &mut self,
        correlation_id: CorrelationId,
        base_key: Key,
        path: &[String],
    ) -> Result<Key, Self::Error>;

    fn query_balance(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract_uref: URef,
        key: Key,
    ) -> Result<U512, Self::Error>;

    fn query_purse_balance_key(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract_uref: URef,
        outer_key: Key,
    ) -> Result<Key, Self::Error>;

    fn query_balance_inner(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<U512, Self::Error>;

    fn get_system_contract_info(
        &mut self,
        correlation_id: CorrelationId,
        pos_public_uref: Key,
    ) -> Result<SystemContractInfo, Self::Error>;

    fn handle_nonce(&mut self, account: &mut Account, nonce: u64) -> Result<(), Self::Error>;
}

impl<R: StateReader<Key, Value>> TrackingCopyExt<R> for TrackingCopy<R> {
    type Error = execution::Error;

    // get an account from tracking copy by addr
    fn get_account(
        &mut self,
        correlation_id: CorrelationId,
        account_address: [u8; 32],
    ) -> Result<Account, Self::Error> {
        let account_key = Key::Account(account_address);

        match self.get(correlation_id, &account_key) {
            Ok(Some(Value::Account(account))) => Ok(account),
            Ok(Some(other)) => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                "Account".to_string(),
                other.type_string(),
            ))),
            Ok(None) => Err(execution::Error::KeyNotFound(account_key)),
            Err(_) => Err(execution::Error::KeyNotFound(account_key)),
        }
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

    // some keys are stored as values under a different key; this function helps retrieve such keys
    fn query_value_key(
        &mut self,
        correlation_id: CorrelationId,
        base_key: Key,
        path: &[String],
    ) -> Result<Key, Self::Error> {
        match self.query(correlation_id, base_key, path) {
            Ok(query_result) => match query_result {
                ::tracking_copy::QueryResult::Success(value) => match value {
                    Value::Key(key) => Ok(key),
                    other => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                        "Value::Key".to_string(),
                        other.type_string(),
                    ))),
                },
                ::tracking_copy::QueryResult::ValueNotFound(msg) => {
                    Err(execution::Error::URefNotFound(msg))
                }
            },
            Err(_) => Err(execution::Error::KeyNotFound(base_key)),
        }
    }

    fn query_balance(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract_uref: URef,
        key: Key,
    ) -> Result<U512, Self::Error> {
        let purse_balance_key =
            match self.query_purse_balance_key(correlation_id, mint_contract_uref, key) {
                Ok(key) => key,
                Err(error) => {
                    return Err(error);
                }
            };

        self.query_balance_inner(correlation_id, purse_balance_key)
    }

    fn query_purse_balance_key(
        &mut self,
        correlation_id: CorrelationId,
        mint_contract_uref: URef,
        outer_key: Key,
    ) -> Result<Key, Self::Error> {
        let bytes = match outer_key.as_uref() {
            Some(uref) => match uref.addr().to_bytes() {
                Ok(bytes) => bytes,
                Err(error) => {
                    return Err(execution::Error::BytesRepr(error));
                }
            },
            None => {
                return Err(execution::Error::URefNotFound(
                    "public_purse_balance".to_string(),
                ));
            }
        };

        let balance_mapping_key = Key::local(mint_contract_uref.addr(), &bytes);

        self.query_value_key(correlation_id, balance_mapping_key, &[])
    }

    fn query_balance_inner(
        &mut self,
        correlation_id: CorrelationId,
        key: Key,
    ) -> Result<U512, Self::Error> {
        match self.query(correlation_id, key, &[]) {
            Ok(query_result) => match query_result {
                QueryResult::Success(value) => match value {
                    Value::UInt512(balance) => Ok(balance),
                    other => Err(execution::Error::TypeMismatch(TypeMismatch::new(
                        "Value::UInt512".to_string(),
                        other.type_string(),
                    ))),
                },
                ::tracking_copy::QueryResult::ValueNotFound(_) => {
                    Err(execution::Error::KeyNotFound(key))
                }
            },
            Err(_) => Err(execution::Error::KeyNotFound(key)),
        }
    }

    // get urefs, pointer, and bytes for a system contract
    fn get_system_contract_info(
        &mut self,
        correlation_id: CorrelationId,
        public_key: Key,
    ) -> Result<SystemContractInfo, Self::Error> {
        let inner_uref_key = match self
            .get(correlation_id, &public_key)
            .map_err(|_| execution::Error::KeyNotFound(public_key))
            .unwrap()
        {
            Some(Value::Key(key)) => key.normalize(),
            _ => return Err(execution::Error::KeyNotFound(public_key)),
        };

        let inner_uref = match inner_uref_key {
            Key::URef(uref) => uref,
            _ => return Err(execution::Error::URefNotFound(public_key.to_string())),
        };

        let contract_value = match self
            .get(correlation_id, &inner_uref_key)
            .map_err(|_| execution::Error::KeyNotFound(inner_uref_key))
            .unwrap()
        {
            Some(contract_value) => contract_value,
            None => return Err(execution::Error::KeyNotFound(inner_uref_key)),
        };

        let contract = match contract_value {
            Value::Contract(contract) => contract,
            other => {
                return Err(execution::Error::TypeMismatch(TypeMismatch::new(
                    "Contract".to_string(),
                    other.type_string(),
                )))
            }
        };

        let module_bytes = contract.bytes().to_vec();

        Ok(SystemContractInfo::new(
            public_key,
            inner_uref,
            inner_uref_key,
            contract,
            module_bytes,
        ))
    }
}
