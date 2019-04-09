use crate::bytesrepr;
use alloc::vec::Vec;
use bytesrepr::{Error, ToBytes};

pub trait ArgsParser {
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error>;
}

impl<T> ArgsParser for T
where
    T: ToBytes,
{
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
        let bytes = ToBytes::to_bytes(self)?;
        Ok(vec![bytes])
    }
}

impl<T1, T2> ArgsParser for (T1, T2)
where
    T1: ToBytes,
    T2: ToBytes,
{
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
        let bytes1 = ToBytes::to_bytes(&self.0)?;
        let bytes2 = ToBytes::to_bytes(&self.1)?;
        Ok(vec![bytes1, bytes2])
    }
}

impl<T1, T2, T3> ArgsParser for (T1, T2, T3)
where
    T1: ToBytes,
    T2: ToBytes,
    T3: ToBytes,
{
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
        let bytes1 = ToBytes::to_bytes(&self.0)?;
        let bytes2 = ToBytes::to_bytes(&self.1)?;
        let bytes3 = ToBytes::to_bytes(&self.2)?;
        Ok(vec![bytes1, bytes2, bytes3])
    }
}

impl<T1, T2, T3, T4> ArgsParser for (T1, T2, T3, T4)
where
    T1: ToBytes,
    T2: ToBytes,
    T3: ToBytes,
    T4: ToBytes,
{
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
        let bytes1 = ToBytes::to_bytes(&self.0)?;
        let bytes2 = ToBytes::to_bytes(&self.1)?;
        let bytes3 = ToBytes::to_bytes(&self.2)?;
        let bytes4 = ToBytes::to_bytes(&self.3)?;
        Ok(vec![bytes1, bytes2, bytes3, bytes4])
    }
}

impl<T1, T2, T3, T4, T5> ArgsParser for (T1, T2, T3, T4, T5)
where
    T1: ToBytes,
    T2: ToBytes,
    T3: ToBytes,
    T4: ToBytes,
    T5: ToBytes,
{
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
        let bytes1 = ToBytes::to_bytes(&self.0)?;
        let bytes2 = ToBytes::to_bytes(&self.1)?;
        let bytes3 = ToBytes::to_bytes(&self.2)?;
        let bytes4 = ToBytes::to_bytes(&self.3)?;
        let bytes5 = ToBytes::to_bytes(&self.4)?;
        Ok(vec![bytes1, bytes2, bytes3, bytes4, bytes5])
    }
}

impl<T1, T2, T3, T4, T5, T6> ArgsParser for (T1, T2, T3, T4, T5, T6)
where
    T1: ToBytes,
    T2: ToBytes,
    T3: ToBytes,
    T4: ToBytes,
    T5: ToBytes,
    T6: ToBytes,
{
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
        let bytes1 = ToBytes::to_bytes(&self.0)?;
        let bytes2 = ToBytes::to_bytes(&self.1)?;
        let bytes3 = ToBytes::to_bytes(&self.2)?;
        let bytes4 = ToBytes::to_bytes(&self.3)?;
        let bytes5 = ToBytes::to_bytes(&self.4)?;
        let bytes6 = ToBytes::to_bytes(&self.5)?;
        Ok(vec![bytes1, bytes2, bytes3, bytes4, bytes5, bytes6])
    }
}

impl<T1, T2, T3, T4, T5, T6, T7> ArgsParser for (T1, T2, T3, T4, T5, T6, T7)
where
    T1: ToBytes,
    T2: ToBytes,
    T3: ToBytes,
    T4: ToBytes,
    T5: ToBytes,
    T6: ToBytes,
    T7: ToBytes,
{
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
        let bytes1 = ToBytes::to_bytes(&self.0)?;
        let bytes2 = ToBytes::to_bytes(&self.1)?;
        let bytes3 = ToBytes::to_bytes(&self.2)?;
        let bytes4 = ToBytes::to_bytes(&self.3)?;
        let bytes5 = ToBytes::to_bytes(&self.4)?;
        let bytes6 = ToBytes::to_bytes(&self.5)?;
        let bytes7 = ToBytes::to_bytes(&self.6)?;
        Ok(vec![bytes1, bytes2, bytes3, bytes4, bytes5, bytes6, bytes7])
    }
}

impl<T1, T2, T3, T4, T5, T6, T7, T8> ArgsParser for (T1, T2, T3, T4, T5, T6, T7, T8)
where
    T1: ToBytes,
    T2: ToBytes,
    T3: ToBytes,
    T4: ToBytes,
    T5: ToBytes,
    T6: ToBytes,
    T7: ToBytes,
    T8: ToBytes,
{
    fn parse(&self) -> Result<Vec<Vec<u8>>, Error> {
        let bytes1 = ToBytes::to_bytes(&self.0)?;
        let bytes2 = ToBytes::to_bytes(&self.1)?;
        let bytes3 = ToBytes::to_bytes(&self.2)?;
        let bytes4 = ToBytes::to_bytes(&self.3)?;
        let bytes5 = ToBytes::to_bytes(&self.4)?;
        let bytes6 = ToBytes::to_bytes(&self.5)?;
        let bytes7 = ToBytes::to_bytes(&self.6)?;
        let bytes8 = ToBytes::to_bytes(&self.7)?;
        Ok(vec![
            bytes1, bytes2, bytes3, bytes4, bytes5, bytes6, bytes7, bytes8,
        ])
    }
}
