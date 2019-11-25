#![no_std]

#[cfg(feature = "use_std")]
#[macro_use]
extern crate std;

use core::ops::Add;
use core::ops::Sub;

#[derive(PartialEq, Debug)]
pub enum ERC20TransferError {
    NotEnoughBalance
}

pub trait ERC20Trait<Amount: From<u32> + Add<Output=Amount> + Sub<Output=Amount> + PartialOrd + Copy, Address> {
    fn read_balance(address: &Address) -> Option<Amount>;
    fn save_balance(address: &Address, balance: Amount);
    fn read_total_supply() -> Option<Amount>;
    fn save_total_supply(total_supply: Amount);

    fn mint(address: &Address, amount: Amount) {
        let address_balance = Self::balance_of(address);
        let total_supply = Self::total_supply();
        Self::save_balance(&address, address_balance + amount);
        Self::save_total_supply(total_supply + amount);
    }

    fn transfer(sender: &Address, recipient: &Address, amount: Amount) -> Result<(), ERC20TransferError> {
        let sender_balance = Self::balance_of(sender);
        if amount > sender_balance {
            Err(ERC20TransferError::NotEnoughBalance)
        } else {
            let recipient_balance = Self::balance_of(recipient);
            Self::save_balance(&sender, sender_balance - amount);
            Self::save_balance(&recipient, recipient_balance + amount);
            Ok(())
        }
    }

    fn balance_of(address: &Address) -> Amount {
        match Self::read_balance(address) {
            Some(amount) => amount,
            None => Amount::from(0)
        }
    }
    
    fn total_supply() -> Amount {
        match Self::read_total_supply() {
            Some(amount) => amount,
            None => Amount::from(0)
        }
    }
}


#[cfg(test)]
mod tests {
    use super::ERC20Trait;
    use super::ERC20TransferError;

    use std::cell::RefCell;
    use std::collections::HashMap;

    type Amount = u64;
    type Address = u8;
    
    thread_local!{
        static BALANCES: RefCell<HashMap<Address, Amount>> = RefCell::new(HashMap::new());
        static TOTAL_SUPPLY: RefCell<Amount> = RefCell::new(0);
    }

    struct Token;

    impl ERC20Trait<Amount, Address> for Token {
        fn read_balance(address: &Address) -> Option<Amount> {
            BALANCES.with(|balances| {
                match balances.borrow().get(address) {
                    Some(value) => Some(value.clone()),
                    None => None
                }
            })
        }

        fn save_balance(address: &Address, balance: Amount) {
            BALANCES.with(|balances| {
                balances.borrow_mut().insert(*address, balance)
            });
        }

        fn read_total_supply() -> Option<Amount> {
            TOTAL_SUPPLY.with(|total_supply| {
                Some(*total_supply.borrow())
            })
        }

        fn save_total_supply(new_total_supply: Amount) {
            TOTAL_SUPPLY.with(|total_supply| {
                *total_supply.borrow_mut() = new_total_supply;
            });
        }
    }

    const ADDRESS_1: u8 = 1u8;
    const ADDRESS_2: u8 = 2u8;
    
    #[test]
    fn test_initial_conditions() {
        assert_eq!(Token::balance_of(&ADDRESS_1), 0);
        assert_eq!(Token::balance_of(&ADDRESS_2), 0);
        assert_eq!(Token::total_supply(), 0);
    }

    #[test]
    fn test_mint() {
        Token::mint(&ADDRESS_1, 10);
        assert_eq!(Token::balance_of(&ADDRESS_1), 10);
        assert_eq!(Token::balance_of(&ADDRESS_2), 0);
        assert_eq!(Token::total_supply(), 10);
    }

    #[test]
    fn test_transfer() {
        Token::mint(&ADDRESS_1, 10);
        let transfer_result = Token::transfer(&ADDRESS_1, &ADDRESS_2, 3);
        assert!(transfer_result.is_ok());
        assert_eq!(Token::balance_of(&ADDRESS_1), 7);
        assert_eq!(Token::balance_of(&ADDRESS_2), 3);
        assert_eq!(Token::total_supply(), 10);
    }

    #[test]
    fn test_transfer_too_much() {
        Token::mint(&ADDRESS_1, 10);
        let transfer_result = Token::transfer(&ADDRESS_1, &ADDRESS_2, 20);
        assert_eq!(transfer_result.unwrap_err(), ERC20TransferError::NotEnoughBalance);
        assert_eq!(Token::balance_of(&ADDRESS_1), 10);
        assert_eq!(Token::balance_of(&ADDRESS_2), 0);
        assert_eq!(Token::total_supply(), 10);
    }
}
