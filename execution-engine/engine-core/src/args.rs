use wasmi::{FromRuntimeValue, RuntimeArgs, Trap};

pub trait Args
where
    Self: Sized,
{
    fn parse(args: RuntimeArgs) -> Result<Self, Trap>;
}

impl Args for u32 {
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        args.nth_checked(0)
    }
}

impl Args for usize {
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a0: u32 = args.nth_checked(0)?;
        Ok(a0 as usize)
    }
}

impl<T1, T2> Args for (T1, T2)
where
    T1: FromRuntimeValue + Sized,
    T2: FromRuntimeValue + Sized,
{
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a0: T1 = args.nth_checked(0)?;
        let a1: T2 = args.nth_checked(1)?;
        Ok((a0, a1))
    }
}

impl<T1, T2, T3> Args for (T1, T2, T3)
where
    T1: FromRuntimeValue + Sized,
    T2: FromRuntimeValue + Sized,
    T3: FromRuntimeValue + Sized,
{
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a0: T1 = args.nth_checked(0)?;
        let a1: T2 = args.nth_checked(1)?;
        let a2: T3 = args.nth_checked(2)?;
        Ok((a0, a1, a2))
    }
}

impl<T1, T2, T3, T4> Args for (T1, T2, T3, T4)
where
    T1: FromRuntimeValue + Sized,
    T2: FromRuntimeValue + Sized,
    T3: FromRuntimeValue + Sized,
    T4: FromRuntimeValue + Sized,
{
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a0: T1 = args.nth_checked(0)?;
        let a1: T2 = args.nth_checked(1)?;
        let a2: T3 = args.nth_checked(2)?;
        let a3: T4 = args.nth_checked(3)?;
        Ok((a0, a1, a2, a3))
    }
}

impl<T1, T2, T3, T4, T5> Args for (T1, T2, T3, T4, T5)
where
    T1: FromRuntimeValue + Sized,
    T2: FromRuntimeValue + Sized,
    T3: FromRuntimeValue + Sized,
    T4: FromRuntimeValue + Sized,
    T5: FromRuntimeValue + Sized,
{
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a0: T1 = args.nth_checked(0)?;
        let a1: T2 = args.nth_checked(1)?;
        let a2: T3 = args.nth_checked(2)?;
        let a3: T4 = args.nth_checked(3)?;
        let a4: T5 = args.nth_checked(4)?;
        Ok((a0, a1, a2, a3, a4))
    }
}

impl<T1, T2, T3, T4, T5, T6> Args for (T1, T2, T3, T4, T5, T6)
where
    T1: FromRuntimeValue + Sized,
    T2: FromRuntimeValue + Sized,
    T3: FromRuntimeValue + Sized,
    T4: FromRuntimeValue + Sized,
    T5: FromRuntimeValue + Sized,
    T6: FromRuntimeValue + Sized,
{
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a0: T1 = args.nth_checked(0)?;
        let a1: T2 = args.nth_checked(1)?;
        let a2: T3 = args.nth_checked(2)?;
        let a3: T4 = args.nth_checked(3)?;
        let a4: T5 = args.nth_checked(4)?;
        let a5: T6 = args.nth_checked(5)?;
        Ok((a0, a1, a2, a3, a4, a5))
    }
}
