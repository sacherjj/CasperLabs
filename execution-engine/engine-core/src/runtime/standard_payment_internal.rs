use lazy_static::lazy_static;

use contract::args_parser::ArgsParser;
use engine_shared::stored_value::StoredValue;
use engine_storage::global_state::StateReader;
use standard_payment::{AccountProvider, MintProvider, ProofOfStakeProvider, StandardPayment};
use types::{system_contract_errors, ApiError, Key, RuntimeArgs, URef, U512};

use crate::{execution, runtime::Runtime};

lazy_static! {
    static ref SERIALIZED_GET_PAYMENT_PURSE: RuntimeArgs = {
        let args = ArgsParser::parse(("get_payment_purse",))
            .expect("args should convert to `Vec<CLValue>`");
        RuntimeArgs::from(args)
    };
}

impl<'a, R> AccountProvider for Runtime<'a, R>
where
    R: StateReader<Key, StoredValue>,
    R::Error: Into<execution::Error>,
{
    fn get_main_purse(&self) -> Result<URef, ApiError> {
        self.context
            .get_main_purse()
            .map_err(|_| ApiError::InvalidPurse)
    }
}

impl<'a, R> MintProvider for Runtime<'a, R>
where
    R: StateReader<Key, StoredValue>,
    R::Error: Into<execution::Error>,
{
    fn transfer_purse_to_purse(
        &mut self,
        source: URef,
        target: URef,
        amount: U512,
    ) -> Result<(), ApiError> {
        let mint_contract_key = Key::from(self.get_mint_contract());
        self.mint_transfer(mint_contract_key, source, target, amount)
            .map_err(|error| match error {
                execution::Error::SystemContract(system_contract_errors::Error::Mint(
                    mint_error,
                )) => ApiError::from(mint_error),
                _ => ApiError::Unhandled,
            })
    }
}

impl<'a, R> ProofOfStakeProvider for Runtime<'a, R>
where
    R: StateReader<Key, StoredValue>,
    R::Error: Into<execution::Error>,
{
    fn get_payment_purse(&mut self) -> Result<URef, ApiError> {
        let pos_contract_key = self.get_pos_contract().into();

        let cl_value = self
            .call_contract(pos_contract_key, SERIALIZED_GET_PAYMENT_PURSE.clone())
            .map_err(|_| {
                ApiError::ProofOfStake(
                    system_contract_errors::pos::Error::PaymentPurseNotFound as u8,
                )
            })?;

        let payment_purse_ref: URef = cl_value.into_t()?;
        Ok(payment_purse_ref)
    }
}

impl<'a, R> StandardPayment for Runtime<'a, R>
where
    R: StateReader<Key, StoredValue>,
    R::Error: Into<execution::Error>,
{
}
