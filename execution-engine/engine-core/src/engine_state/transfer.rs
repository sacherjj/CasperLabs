use engine_shared::{account::Account, newtypes::CorrelationId, stored_value::StoredValue};
use engine_storage::global_state::StateReader;
use std::{cell::RefCell, rc::Rc};
use types::{
    system_contract_errors::mint::Error as MintError, AccessRights, ApiError, Key, RuntimeArgs,
    URef, U512,
};

use crate::{
    engine_state::Error,
    execution::Error as ExecError,
    tracking_copy::{TrackingCopy, TrackingCopyExt},
};

const SOURCE: &str = "source";
const TARGET: &str = "target";
const AMOUNT: &str = "amount";

pub struct TransferRuntimeArgsBuilder {
    inner: RuntimeArgs,
}

impl TransferRuntimeArgsBuilder {
    pub fn new(imputed_runtime_args: RuntimeArgs) -> TransferRuntimeArgsBuilder {
        TransferRuntimeArgsBuilder {
            inner: imputed_runtime_args,
        }
    }

    fn resolve_source_uref<R>(
        &self,
        account: &Account,
        correlation_id: CorrelationId,
        tracking_copy: Rc<RefCell<TrackingCopy<R>>>,
    ) -> Result<URef, Error>
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<ExecError>,
    {
        let imputed_runtime_args = &self.inner;
        let arg_name = SOURCE;
        match imputed_runtime_args.get(arg_name) {
            Some(cl_value) if *cl_value.cl_type() == types::CLType::URef => {
                let uref: URef = match cl_value.clone().into_t() {
                    Ok(uref) => uref,
                    Err(error) => {
                        return Err(Error::Exec(ExecError::Revert(error.into())));
                    }
                };
                if account.main_purse().addr() == uref.addr() {
                    return Ok(uref);
                }

                let normalized_uref = Key::URef(uref).normalize();
                let maybe_named_key = account
                    .named_keys()
                    .values()
                    .find(|&named_key| named_key.normalize() == normalized_uref);
                match maybe_named_key {
                    Some(Key::URef(found_uref)) => {
                        if found_uref.is_writeable() {
                            // it is a valid URef and caller has access but is it a purse URef?
                            // NOTE: continue to use passed in URef, not found URef
                            tracking_copy
                                .borrow_mut()
                                .get_purse_balance_key(correlation_id, uref.into())
                                .map_err(|_| ExecError::Revert(ApiError::InvalidPurse))?;
                            Ok(uref)
                        } else {
                            Err(Error::Exec(ExecError::InvalidAccess {
                                required: AccessRights::WRITE,
                            }))
                        }
                    }
                    Some(key) => Err(Error::Exec(ExecError::TypeMismatch(
                        engine_shared::TypeMismatch::new(
                            "Key::URef".to_string(),
                            key.type_string(),
                        ),
                    ))),
                    None => Err(Error::Exec(ExecError::ForgedReference(uref))),
                }
            }
            Some(_) => Err(Error::Exec(ExecError::Revert(ApiError::InvalidArgument))),
            None => Ok(account.main_purse()), // if no source purse passed use account main purse
        }
    }

    fn resolve_target_uref<R>(
        &self,
        correlation_id: CorrelationId,
        tracking_copy: Rc<RefCell<TrackingCopy<R>>>,
    ) -> Result<URef, Error>
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<ExecError>,
    {
        let imputed_runtime_args = &self.inner;
        let arg_name = TARGET;
        match imputed_runtime_args.get(arg_name) {
            Some(cl_value) if *cl_value.cl_type() == types::CLType::URef => {
                let uref = match cl_value.clone().into_t() {
                    Ok(uref) => uref,
                    Err(error) => {
                        return Err(Error::Exec(ExecError::Revert(error.into())));
                    }
                };
                Ok(uref)
            }
            Some(cl_value)
                if *cl_value.cl_type()
                    == types::CLType::FixedList(Box::new(types::CLType::U8), 32) =>
            {
                let account_key: Key = {
                    let hash = match cl_value.clone().into_t() {
                        Ok(hash) => hash,
                        Err(error) => {
                            return Err(Error::Exec(ExecError::Revert(error.into())));
                        }
                    };
                    Key::Account(hash)
                };
                match account_key.into_account() {
                    Some(public_key) => {
                        match tracking_copy
                            .borrow_mut()
                            .get_account(correlation_id, public_key)
                        {
                            Ok(account) => Ok(account.main_purse()),
                            Err(error) => Err(Error::Exec(error)),
                        }
                    }
                    None => Err(Error::Exec(ExecError::Revert(ApiError::Transfer))),
                }
            }
            Some(cl_value) if *cl_value.cl_type() == types::CLType::Key => {
                let account_key: Key = match cl_value.clone().into_t() {
                    Ok(key) => key,
                    Err(error) => {
                        return Err(Error::Exec(ExecError::Revert(error.into())));
                    }
                };
                match account_key.into_account() {
                    Some(public_key) => {
                        match tracking_copy
                            .borrow_mut()
                            .get_account(correlation_id, public_key)
                        {
                            Ok(account) => Ok(account.main_purse()),
                            Err(error) => Err(Error::Exec(error)),
                        }
                    }
                    None => Err(Error::Exec(ExecError::Revert(ApiError::Transfer))),
                }
            }
            Some(_) => Err(Error::Exec(ExecError::Revert(ApiError::InvalidArgument))),
            None => Err(Error::Exec(ExecError::Revert(ApiError::MissingArgument))),
        }
    }

    fn resolve_amount(&self) -> Result<U512, Error> {
        let imputed_runtime_args = &self.inner;
        match imputed_runtime_args.get(AMOUNT) {
            Some(amount_value) if *amount_value.cl_type() == types::CLType::U512 => {
                match amount_value.clone().into_t::<U512>() {
                    Ok(amount) => {
                        if amount == U512::zero() {
                            Err(Error::Exec(ExecError::Revert(ApiError::Transfer)))
                        } else {
                            Ok(amount)
                        }
                    }
                    Err(error) => Err(Error::Exec(ExecError::Revert(error.into()))),
                }
            }
            Some(amount_value) if *amount_value.cl_type() == types::CLType::U64 => {
                match amount_value.clone().into_t::<u64>() {
                    Ok(amount) => match amount {
                        0 => Err(Error::Exec(ExecError::Revert(ApiError::Transfer))),
                        _ => Ok(U512::from(amount)),
                    },
                    Err(error) => Err(Error::Exec(ExecError::Revert(error.into()))),
                }
            }
            Some(_) => Err(Error::Exec(ExecError::Revert(ApiError::InvalidArgument))),
            None => Err(Error::Exec(ExecError::Revert(ApiError::MissingArgument))),
        }
    }

    pub fn build<R>(
        self,
        correlation_id: CorrelationId,
        account: &Account,
        tracking_copy: Rc<RefCell<TrackingCopy<R>>>,
    ) -> Result<RuntimeArgs, Error>
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<ExecError>,
    {
        let runtime_args = {
            let mut runtime_args = RuntimeArgs::new();

            let source_uref =
                self.resolve_source_uref(account, correlation_id, Rc::clone(&tracking_copy))?;

            let target_uref =
                self.resolve_target_uref(correlation_id, Rc::clone(&tracking_copy))?;

            if source_uref.addr() == target_uref.addr() {
                return Err(ExecError::Revert(ApiError::InvalidPurse).into());
            }

            tracking_copy
                .borrow_mut()
                .get_purse_balance_key(correlation_id, target_uref.into())
                .map_err(|_| ExecError::Revert(ApiError::Mint(MintError::DestNotFound as u8)))?;

            let amount = self.resolve_amount()?;

            runtime_args.insert(SOURCE, source_uref);
            runtime_args.insert(TARGET, target_uref);
            runtime_args.insert(AMOUNT, amount);

            runtime_args
        };

        Ok(runtime_args)
    }
}
