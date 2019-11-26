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

#[derive(PartialEq, Debug)]
pub enum ERC20TransferFromError {
    NotEnoughBalance,
    NotEnoughAllowance
}

pub trait ERC20Trait<Amount: From<u32> + Add<Output=Amount> + Sub<Output=Amount> + PartialOrd + Copy, Address> {
    fn read_balance(address: &Address) -> Option<Amount>;
    fn save_balance(address: &Address, balance: Amount);
    fn read_total_supply() -> Option<Amount>;
    fn save_total_supply(total_supply: Amount);
    fn read_allowance(owner: &Address, spender: &Address) -> Option<Amount>;
    fn save_allowance(owner: &Address, spender: &Address, amount: Amount);

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

    fn allowance(owner: &Address, spender: &Address) -> Amount {
        match Self::read_allowance(owner, spender) {
            Some(amount) => amount,
            None => Amount::from(0)
        }
    }

    fn approve(owner: &Address, spender: &Address, amount: Amount) {
        Self::save_allowance(owner, spender, amount)
    }

    fn transfer_from(spender: &Address, owner: &Address, recipient: &Address, amount: Amount
        ) -> Result<(), ERC20TransferFromError> {
        let allowance = Self::allowance(owner, spender);
        let balance = Self::balance_of(owner);
        if amount > allowance {
            Err(ERC20TransferFromError::NotEnoughAllowance)
        } else if amount > balance {
            Err(ERC20TransferFromError::NotEnoughBalance)
        } else {
            // Balance was already checked.
            let _result = Self::transfer(owner, recipient, amount);
            Self::approve(owner, spender, allowance - amount);
            Ok(())
        }
    }
}


// Tests start with example implementation of ERC20Trait.
#[cfg(test)]
mod tests {
    use super::ERC20Trait;
    use super::ERC20TransferError;
    use super::ERC20TransferFromError;

    use std::cell::RefCell;
    use std::collections::HashMap;

    type Amount = u64;
    type Address = u8;
    type AddressPair = (Address, Address);
    
    thread_local!{
        static BALANCES: RefCell<HashMap<Address, Amount>> = RefCell::new(HashMap::new());
        static ALLOWANCE: RefCell<HashMap<AddressPair, Amount>> = RefCell::new(HashMap::new());
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

        fn read_allowance(owner: &Address, spender: &Address) -> Option<Amount> {
            ALLOWANCE.with(|allowance| {
                match allowance.borrow().get(&(*owner, *spender)) {
                    Some(value) => Some(value.clone()),
                    None => None
                }
            })
        }

        fn save_allowance(owner: &Address, spender: &Address, amount: Amount) {
            ALLOWANCE.with(|allowance| {
                allowance.borrow_mut().insert((*owner, *spender), amount)
            });
        }        
    }

    const ADDRESS_1: Address = 1;
    const ADDRESS_2: Address = 2;
    const ADDRESS_3: Address = 3;
    
    #[test]
    fn test_initial_balances() {
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

    #[test]
    fn test_initial_allowance() {
        assert_eq!(Token::allowance(&ADDRESS_1, &ADDRESS_2), 0);
        assert_eq!(Token::allowance(&ADDRESS_2, &ADDRESS_1), 0);
    }

    #[test]
    fn test_approvals() {
        Token::approve(&ADDRESS_1, &ADDRESS_2, 10);
        assert_eq!(Token::allowance(&ADDRESS_1, &ADDRESS_2), 10);
        assert_eq!(Token::allowance(&ADDRESS_2, &ADDRESS_1), 0);
        Token::approve(&ADDRESS_1, &ADDRESS_2, 2);
        assert_eq!(Token::allowance(&ADDRESS_1, &ADDRESS_2), 2);
        assert_eq!(Token::allowance(&ADDRESS_2, &ADDRESS_1), 0);
    }

    #[test]
    fn test_transfer_from() {
        Token::mint(&ADDRESS_1, 10);
        Token::approve(&ADDRESS_1, &ADDRESS_2, 5);
        let transfer_result = Token::transfer_from(&ADDRESS_2, &ADDRESS_1, &ADDRESS_3, 3);
        assert!(transfer_result.is_ok());
        assert_eq!(Token::allowance(&ADDRESS_1, &ADDRESS_2), 2);
        assert_eq!(Token::balance_of(&ADDRESS_1), 7);
        assert_eq!(Token::balance_of(&ADDRESS_2), 0);
        assert_eq!(Token::balance_of(&ADDRESS_3), 3);
    }

    #[test]
    fn test_transfer_from_too_much() {
        Token::mint(&ADDRESS_1, 10);
        Token::approve(&ADDRESS_1, &ADDRESS_2, 25);
        let transfer_result = Token::transfer_from(&ADDRESS_2, &ADDRESS_1, &ADDRESS_3, 20);
        assert_eq!(transfer_result.unwrap_err(), ERC20TransferFromError::NotEnoughBalance);
        assert_eq!(Token::allowance(&ADDRESS_1, &ADDRESS_2), 25);
        assert_eq!(Token::balance_of(&ADDRESS_1), 10);
        assert_eq!(Token::balance_of(&ADDRESS_2), 0);
        assert_eq!(Token::balance_of(&ADDRESS_3), 0);
    }

    #[test]
    fn test_transfer_from_too_low_allowance() {
        Token::mint(&ADDRESS_1, 10);
        Token::approve(&ADDRESS_1, &ADDRESS_2, 3);
        let transfer_result = Token::transfer_from(&ADDRESS_2, &ADDRESS_1, &ADDRESS_3, 5);
        assert_eq!(transfer_result.unwrap_err(), ERC20TransferFromError::NotEnoughAllowance);
        assert_eq!(Token::allowance(&ADDRESS_1, &ADDRESS_2), 3);
        assert_eq!(Token::balance_of(&ADDRESS_1), 10);
        assert_eq!(Token::balance_of(&ADDRESS_2), 0);
        assert_eq!(Token::balance_of(&ADDRESS_3), 0);
    }
}
