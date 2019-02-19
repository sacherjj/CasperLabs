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
        let a: u32 = args.nth_checked(0)?;
        Ok(a as usize)
    }
}

impl<T1, T2> Args for (T1, T2)
where
    T1: FromRuntimeValue + Sized,
    T2: FromRuntimeValue + Sized,
{
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a: T1 = args.nth_checked(0)?;
        let b: T2 = args.nth_checked(1)?;
        Ok((a, b))
    }
}

impl<T1, T2, T3> Args for (T1, T2, T3)
where
    T1: FromRuntimeValue + Sized,
    T2: FromRuntimeValue + Sized,
    T3: FromRuntimeValue + Sized,
{
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a: T1 = args.nth_checked(0)?;
        let b: T2 = args.nth_checked(1)?;
        let c: T3 = args.nth_checked(2)?;
        Ok((a, b, c))
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
        let a: T1 = args.nth_checked(0)?;
        let b: T2 = args.nth_checked(1)?;
        let c: T3 = args.nth_checked(2)?;
        let d: T4 = args.nth_checked(3)?;
        Ok((a, b, c, d))
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
    #[allow(clippy::many_single_char_names)]
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a: T1 = args.nth_checked(0)?;
        let b: T2 = args.nth_checked(1)?;
        let c: T3 = args.nth_checked(2)?;
        let d: T4 = args.nth_checked(3)?;
        let e: T5 = args.nth_checked(4)?;
        Ok((a, b, c, d, e))
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
    #[allow(clippy::many_single_char_names)]
    fn parse(args: RuntimeArgs) -> Result<Self, Trap> {
        let a: T1 = args.nth_checked(0)?;
        let b: T2 = args.nth_checked(1)?;
        let c: T3 = args.nth_checked(2)?;
        let d: T4 = args.nth_checked(3)?;
        let e: T5 = args.nth_checked(4)?;
        let f: T6 = args.nth_checked(5)?;
        Ok((a, b, c, d, e, f))
    }
}
