use crate::capabilities::{Addable, Readable, Writable};
use cl_std::value::U512;

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    InsufficientFunds,
    SourceNotFound,
    DestNotFound,
}

pub trait Mint<A, RW>
where
    A: Addable<U512>,
    RW: Readable<U512> + Writable<U512>,
{
    type PurseId;
    type DepOnlyId;

    fn create(&self, balance: U512) -> Self::PurseId;
    fn lookup(&self, p: Self::PurseId) -> Option<RW>;
    fn dep_lookup(&self, p: Self::DepOnlyId) -> Option<A>;

    fn transfer(
        &self,
        source: Self::PurseId,
        dest: Self::DepOnlyId,
        amount: U512,
    ) -> Result<(), Error> {
        let source_bal = self.lookup(source).ok_or(Error::SourceNotFound)?;
        let source_value = source_bal.read();
        if amount > source_value {
            return Err(Error::InsufficientFunds);
        }

        let dest_bal = self.dep_lookup(dest).ok_or(Error::DestNotFound)?;
        source_bal.write(source_value - amount);
        dest_bal.add(amount);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use alloc::collections::BTreeMap;
    use alloc::rc::Rc;
    use cl_std::value::U512;
    use core::cell::{Cell, RefCell};
    use mint::{Error, Mint};

    type Balance = Rc<Cell<U512>>;

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    struct FullId(u32);

    impl FullId {
        fn to_dep(self) -> DepId {
            DepId(self.0)
        }
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    struct DepId(u32);

    struct SimpleMint(RefCell<BTreeMap<u32, Balance>>, Cell<u32>);

    impl SimpleMint {
        pub fn new() -> Self {
            SimpleMint(RefCell::new(BTreeMap::new()), Cell::new(0))
        }

        pub fn balance(&self, id: &u32) -> Option<U512> {
            self.0.borrow().get(id).map(|r| Cell::get(r))
        }
    }

    impl Mint<Balance, Balance> for SimpleMint {
        type PurseId = FullId;
        type DepOnlyId = DepId;

        fn create(&self, balance: U512) -> Self::PurseId {
            let id = self.1.get();
            self.1.set(id + 1);

            let balance = Rc::new(Cell::new(balance));
            self.0.borrow_mut().insert(id, balance);
            FullId(id)
        }

        fn lookup(&self, p: Self::PurseId) -> Option<Balance> {
            self.0.borrow().get(&p.0).map(Rc::clone)
        }

        fn dep_lookup(&self, p: Self::DepOnlyId) -> Option<Balance> {
            self.0.borrow().get(&p.0).map(Rc::clone)
        }
    }

    #[test]
    fn transfer_success() {
        let mint = SimpleMint::new();
        let balance1 = U512::from(150);
        let balance2 = U512::from(30);
        let transfer_amount = U512::from(75);

        let purse1 = mint.create(balance1);
        let purse2 = mint.create(balance2).to_dep();

        mint.transfer(purse1.clone(), purse2.clone(), transfer_amount)
            .expect("transfer errored when it should not.");

        let b1 = mint.balance(&purse1.0).unwrap();
        let b2 = mint.balance(&purse2.0).unwrap();

        assert_eq!(balance1 - transfer_amount, b1);
        assert_eq!(balance2 + transfer_amount, b2);
    }

    #[test]
    fn transfer_overdraft() {
        let mint = SimpleMint::new();
        let balance1 = U512::from(150);
        let balance2 = U512::from(30);
        let transfer_amount = U512::from(1000);

        let purse1 = mint.create(balance1);
        let purse2 = mint.create(balance2).to_dep();

        assert_eq!(
            Err(Error::InsufficientFunds),
            mint.transfer(purse1.clone(), purse2.clone(), transfer_amount)
        );

        let b1 = mint.balance(&purse1.0).unwrap();
        let b2 = mint.balance(&purse2.0).unwrap();

        // balances remain unchanged
        assert_eq!(balance1, b1);
        assert_eq!(balance2, b2);
    }

    #[test]
    fn transfer_dest_not_exist() {
        let mint = SimpleMint::new();
        let balance1 = U512::from(150);
        let transfer_amount = U512::from(75);

        let purse1 = mint.create(balance1);
        let purse2 = DepId(purse1.clone().0 + 1);

        assert_eq!(
            Err(Error::DestNotFound),
            mint.transfer(purse1.clone(), purse2, transfer_amount)
        );

        let b1 = mint.balance(&purse1.0).unwrap();
        // balance remains unchanged
        assert_eq!(balance1, b1);
    }

    #[test]
    fn transfer_source_not_exist() {
        let mint = SimpleMint::new();
        let balance1 = U512::from(150);
        let transfer_amount = U512::from(75);

        let purse1 = mint.create(balance1);
        let purse2 = FullId(purse1.clone().0 + 1);

        assert_eq!(
            Err(Error::SourceNotFound),
            mint.transfer(purse2, purse1.clone().to_dep(), transfer_amount)
        );

        let b1 = mint.balance(&purse1.0).unwrap();
        // balance remains unchanged
        assert_eq!(balance1, b1);
    }
}
