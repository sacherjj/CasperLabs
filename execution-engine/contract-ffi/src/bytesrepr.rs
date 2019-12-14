// Can be removed once https://github.com/rust-lang/rustfmt/issues/3362 is resolved.
#[rustfmt::skip]
use alloc::vec;
use alloc::{
    collections::{BTreeMap, TryReserveError},
    string::String,
    vec::Vec,
};
use core::mem::{size_of, MaybeUninit};

use failure::Fail;

use crate::value::{ProtocolVersion, SemVer};

pub const I32_SIZE: usize = size_of::<i32>();
pub const U8_SIZE: usize = size_of::<u8>();
pub const U16_SIZE: usize = size_of::<u16>();
pub const U32_SIZE: usize = size_of::<u32>();
pub const U64_SIZE: usize = size_of::<u64>();
pub const U128_SIZE: usize = size_of::<u128>();
pub const U256_SIZE: usize = U128_SIZE * 2;
pub const U512_SIZE: usize = U256_SIZE * 2;
pub const OPTION_SIZE: usize = 1;
pub const SEM_VER_SIZE: usize = 12;

pub const N32: usize = 32;

pub trait ToBytes {
    fn to_bytes(&self) -> Result<Vec<u8>, Error>;
    fn into_bytes(self) -> Result<Vec<u8>, Error>
    where
        Self: Sized,
    {
        self.to_bytes()
    }
}

pub trait FromBytes: Sized {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error>;
    fn from_vec(bytes: Vec<u8>) -> Result<(Self, Vec<u8>), Error> {
        Self::from_bytes(bytes.as_slice()).map(|(x, remainder)| (x, Vec::from(remainder)))
    }
}

#[derive(Debug, Fail, PartialEq, Eq, Clone)]
#[repr(u8)]
pub enum Error {
    #[fail(display = "Deserialization error: early end of stream")]
    EarlyEndOfStream = 0,

    #[fail(display = "Deserialization error: formatting error")]
    FormattingError,

    #[fail(display = "Deserialization error: left-over bytes")]
    LeftOverBytes,

    #[fail(display = "Serialization error: out of memory")]
    OutOfMemoryError,
}

impl From<TryReserveError> for Error {
    fn from(_: TryReserveError) -> Error {
        Error::OutOfMemoryError
    }
}

pub fn deserialize<T: FromBytes>(bytes: Vec<u8>) -> Result<T, Error> {
    let (t, remainder) = T::from_vec(bytes)?;
    if remainder.is_empty() {
        Ok(t)
    } else {
        Err(Error::LeftOverBytes)
    }
}

pub fn serialize(t: impl ToBytes) -> Result<Vec<u8>, Error> {
    t.into_bytes()
}

pub fn safe_split_at(bytes: &[u8], n: usize) -> Result<(&[u8], &[u8]), Error> {
    if n > bytes.len() {
        Err(Error::EarlyEndOfStream)
    } else {
        Ok(bytes.split_at(n))
    }
}

impl ToBytes for bool {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        u8::from(*self).to_bytes()
    }
}

impl FromBytes for bool {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        match bytes.split_first() {
            None => Err(Error::EarlyEndOfStream),
            Some((byte, rem)) => match byte {
                1 => Ok((true, rem)),
                0 => Ok((false, rem)),
                _ => Err(Error::FormattingError),
            },
        }
    }
}

impl ToBytes for u8 {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        Ok(vec![*self])
    }
}

impl FromBytes for u8 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        match bytes.split_first() {
            None => Err(Error::EarlyEndOfStream),
            Some((byte, rem)) => Ok((*byte, rem)),
        }
    }
}

impl ToBytes for i32 {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        Ok(self.to_le_bytes().to_vec())
    }
}

impl FromBytes for i32 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut result: [u8; I32_SIZE] = [0u8; I32_SIZE];
        let (bytes, rem) = safe_split_at(bytes, I32_SIZE)?;
        result.copy_from_slice(bytes);
        Ok((i32::from_le_bytes(result), rem))
    }
}

impl ToBytes for u32 {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        Ok(self.to_le_bytes().to_vec())
    }
}

impl FromBytes for u32 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut result: [u8; U32_SIZE] = [0u8; U32_SIZE];
        let (bytes, rem) = safe_split_at(bytes, U32_SIZE)?;
        result.copy_from_slice(bytes);
        Ok((u32::from_le_bytes(result), rem))
    }
}

impl ToBytes for u64 {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        Ok(self.to_le_bytes().to_vec())
    }
}

impl FromBytes for u64 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut result: [u8; 8] = [0u8; 8];
        let (bytes, rem) = safe_split_at(bytes, 8)?;
        result.copy_from_slice(bytes);
        Ok((u64::from_le_bytes(result), rem))
    }
}

impl ToBytes for i64 {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        Ok(self.to_le_bytes().to_vec())
    }
}

impl FromBytes for i64 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut result: [u8; 8] = [0u8; 8];
        let (bytes, rem) = safe_split_at(bytes, 8)?;
        result.copy_from_slice(bytes);
        Ok((i64::from_le_bytes(result), rem))
    }
}

impl FromBytes for Vec<u8> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<u8> = Vec::new();
        result.try_reserve_exact(size as usize)?;
        for _ in 0..size {
            let (t, rem): (u8, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for Vec<u8> {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // Return error if size of serialized vector would exceed limit for
        // 32-bit architecture.
        if self.len() >= u32::max_value() as usize - U32_SIZE {
            return Err(Error::OutOfMemoryError);
        }
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(U32_SIZE + size as usize);
        result.extend(size.to_bytes()?);
        result.extend(self);
        Ok(result)
    }
}

impl FromBytes for Vec<i32> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<i32> = Vec::new();
        result.try_reserve_exact(size as usize)?;
        for _ in 0..size {
            let (t, rem): (i32, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl<T: ToBytes> ToBytes for Option<T> {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            Some(v) => {
                let mut value = v.to_bytes()?;
                if value.len() >= u32::max_value() as usize - U8_SIZE {
                    return Err(Error::OutOfMemoryError);
                }
                let mut result: Vec<u8> = Vec::with_capacity(U8_SIZE + value.len());
                result.append(&mut 1u8.to_bytes()?);
                result.append(&mut value);
                Ok(result)
            }
            // In the case of None there is no value to serialize, but we still
            // need to write out a tag to indicate which variant we are using
            None => Ok(0u8.to_bytes()?),
        }
    }
}

impl<T: FromBytes> FromBytes for Option<T> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (tag, rem): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match tag {
            0 => Ok((None, rem)),
            1 => {
                let (t, rem): (T, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Some(t), rem))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

impl ToBytes for Vec<i32> {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        // Return error if size of vector would exceed length of serialized data
        if self.len() * I32_SIZE >= u32::max_value() as usize - U32_SIZE {
            return Err(Error::OutOfMemoryError);
        }
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(U32_SIZE + (I32_SIZE * size as usize));
        result.extend(size.to_bytes()?);
        result.extend(
            self.iter()
                .map(ToBytes::to_bytes)
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten(),
        );
        Ok(result)
    }
}

impl FromBytes for Vec<Vec<u8>> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result = Vec::new();
        result.try_reserve_exact(size as usize)?;
        for _ in 0..size {
            let (v, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(v);
            stream = rem;
        }
        Ok((result, stream))
    }

    fn from_vec(mut bytes: Vec<u8>) -> Result<(Self, Vec<u8>), Error> {
        let (len, remainder) = u32::from_vec(bytes)?;
        bytes = remainder;
        let mut result = Vec::with_capacity(len as usize);
        for _ in 0..len {
            let (v, remainder) = Vec::<u8>::from_vec(bytes)?;
            bytes = remainder;
            result.push(v);
        }
        Ok((result, bytes))
    }
}

impl ToBytes for Vec<Vec<u8>> {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let self_len = self.len();
        let serialized_length =
            self.iter().map(Vec::len).sum::<usize>() + (U32_SIZE as usize * (self_len + 1));
        if serialized_length > u32::max_value() as usize {
            return Err(Error::OutOfMemoryError);
        }

        let mut result: Vec<u8> = Vec::with_capacity(serialized_length);
        result.append(&mut (self_len as u32).into_bytes()?);
        for vec in self.iter() {
            result.append(&mut vec.to_bytes()?)
        }
        Ok(result)
    }

    fn into_bytes(self) -> Result<Vec<u8>, Error> {
        let self_len = self.len();
        let serialized_length =
            self.iter().map(Vec::len).sum::<usize>() + (U32_SIZE as usize * (self_len + 1));
        if serialized_length > u32::max_value() as usize {
            return Err(Error::OutOfMemoryError);
        }

        let mut result: Vec<u8> = Vec::with_capacity(serialized_length);
        result.append(&mut (self_len as u32).into_bytes()?);
        for vec in self.into_iter() {
            result.append(&mut vec.into_bytes()?)
        }
        Ok(result)
    }
}

impl FromBytes for Vec<String> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result = Vec::new();
        result.try_reserve_exact(size as usize)?;
        for _ in 0..size {
            let (s, rem): (String, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(s);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for Vec<String> {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(U32_SIZE);
        result.extend(size.to_bytes()?);
        result.extend(
            self.iter()
                .map(ToBytes::to_bytes)
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten(),
        );
        Ok(result)
    }
}

macro_rules! impl_to_from_bytes_for_array {
    ($($N:literal)+) => {
        $(
            impl<T: ToBytes> ToBytes for [T; $N] {
               default fn to_bytes(&self) -> Result<Vec<u8>, Error> {
                    // Approximation, as `size_of::<T>()` is only roughly equal to the serialized
                    // size of `T`.
                    let approx_size = self.len() * size_of::<T>();
                    if approx_size >= u32::max_value() as usize {
                        return Err(Error::OutOfMemoryError);
                    }

                    let mut result = Vec::with_capacity(approx_size);
                    result.append(&mut ($N as u32).to_bytes()?);

                    for item in self.iter() {
                        result.append(&mut item.to_bytes()?);
                    }

                    Ok(result)
                }
            }

            impl<T: FromBytes> FromBytes for [T; $N] {
               default fn from_bytes(mut bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
                    let (size, remainder) = u32::from_bytes(bytes)?;
                    bytes = remainder;
                    if size != $N as u32 {
                        return Err(Error::FormattingError);
                    }

                    let mut result: MaybeUninit<[T; $N]> = MaybeUninit::uninit();
                    let result_ptr = result.as_mut_ptr() as *mut T;
                    unsafe {
                        for i in 0..$N {
                            let (t, remainder) = match T::from_bytes(bytes) {
                                Ok(success) => success,
                                Err(error) => {
                                    for j in 0..i {
                                        result_ptr.add(j).drop_in_place();
                                    }
                                    return Err(error);
                                }
                            };
                            result_ptr.add(i).write(t);
                            bytes = remainder;
                        }
                        Ok((result.assume_init(), bytes))
                    }
                }
            }
        )+
    }
}

impl_to_from_bytes_for_array! {
     0  1  2  3  4  5  6  7  8  9
    10 11 12 13 14 15 16 17 18 19
    20 21 22 23 24 25 26 27 28 29
    30 31 32
    64 128 256 512
}

macro_rules! impl_byte_array {
    ($($len:expr)+) => {
        $(
            impl ToBytes for [u8; $len] {
                fn to_bytes(&self) -> Result<Vec<u8>, Error> {
                    Ok(self.to_vec())
                }
            }

            impl FromBytes for [u8; $len] {
                fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
                    let (bytes, rem) = safe_split_at(bytes, $len)?;
                    let mut result = [0u8; $len];
                    result.copy_from_slice(bytes);
                    Ok((result, rem))
                }
            }
        )+
    }
}

impl_byte_array! { 4 5 8 32 }

impl ToBytes for String {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        self.as_str().to_bytes()
    }
}

impl FromBytes for String {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (str_bytes, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(bytes)?;
        let result = String::from_utf8(str_bytes).map_err(|_| Error::FormattingError)?;
        Ok((result, rem))
    }
}

impl ToBytes for () {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        Ok(Vec::new())
    }
}

impl FromBytes for () {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        Ok(((), bytes))
    }
}

impl<K, V> ToBytes for BTreeMap<K, V>
where
    K: ToBytes,
    V: ToBytes,
{
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let num_keys = self.len() as u32;
        let bytes = self
            .iter()
            .map(move |(k, v)| {
                let k_bytes = k.to_bytes().map_err(Error::from);
                let v_bytes = v.to_bytes().map_err(Error::from);
                // For each key and value pair create a vector of
                // serialization results
                let mut vs = Vec::with_capacity(2);
                vs.push(k_bytes);
                vs.push(v_bytes);
                vs
            })
            // Flatten iterable of key and value pairs
            .flatten()
            // Collect into a single Result of bytes (if successful)
            .collect::<Result<Vec<_>, _>>()?
            .into_iter()
            .flatten();

        let (lower_bound, _upper_bound) = bytes.size_hint();
        if lower_bound >= u32::max_value() as usize - U32_SIZE {
            return Err(Error::OutOfMemoryError);
        }
        let mut result: Vec<u8> = Vec::with_capacity(U32_SIZE + lower_bound);
        result.append(&mut num_keys.to_bytes()?);
        result.extend(bytes);
        Ok(result)
    }
}

impl<K, V> FromBytes for BTreeMap<K, V>
where
    K: FromBytes + Ord,
    V: FromBytes,
{
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (num_keys, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result = BTreeMap::new();
        for _ in 0..num_keys {
            let (k, rem): (K, &[u8]) = FromBytes::from_bytes(stream)?;
            let (v, rem): (V, &[u8]) = FromBytes::from_bytes(rem)?;
            result.insert(k, v);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for str {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        if self.len() >= u32::max_value() as usize - U32_SIZE {
            return Err(Error::OutOfMemoryError);
        }
        self.as_bytes().to_vec().into_bytes()
    }
}

impl ToBytes for &str {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        (*self).to_bytes()
    }
}

impl<T: ToBytes, E: ToBytes> ToBytes for Result<T, E> {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let (mut variant, mut value) = match self {
            Ok(result) => (1u8.to_bytes()?, result.to_bytes()?),
            Err(error) => (0u8.to_bytes()?, error.to_bytes()?),
        };
        let mut result: Vec<u8> = Vec::with_capacity(U8_SIZE + value.len());
        result.append(&mut variant);
        result.append(&mut value);
        Ok(result)
    }
}

impl<T: FromBytes, E: FromBytes> FromBytes for Result<T, E> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (variant, rem): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match variant {
            0 => {
                let (value, rem): (E, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Err(value), rem))
            }
            1 => {
                let (value, rem): (T, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Ok(value), rem))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

impl ToBytes for SemVer {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut ret: Vec<u8> = Vec::with_capacity(SEM_VER_SIZE);
        ret.append(&mut self.major.to_bytes()?);
        ret.append(&mut self.minor.to_bytes()?);
        ret.append(&mut self.patch.to_bytes()?);
        Ok(ret)
    }
}

impl FromBytes for SemVer {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (major, rem): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let (minor, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (patch, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        Ok((SemVer::new(major, minor, patch), rem))
    }
}

impl ToBytes for ProtocolVersion {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let value_bytes = self.value().to_bytes()?;
        Ok(value_bytes.to_vec())
    }
}

impl FromBytes for ProtocolVersion {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (version, rem): (SemVer, &[u8]) = FromBytes::from_bytes(bytes)?;
        let protocol_version = ProtocolVersion::new(version);
        Ok((protocol_version, rem))
    }
}

impl<T1: ToBytes> ToBytes for (T1,) {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        Ok(result)
    }
}

impl<T1: FromBytes> FromBytes for (T1,) {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        Ok(((t1,), remainder))
    }
}

impl<T1: ToBytes, T2: ToBytes> ToBytes for (T1, T2) {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        Ok(result)
    }
}

impl<T1: FromBytes, T2: FromBytes> FromBytes for (T1, T2) {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        Ok(((t1, t2), remainder))
    }
}

impl<T1: ToBytes, T2: ToBytes, T3: ToBytes> ToBytes for (T1, T2, T3) {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        result.append(&mut self.2.to_bytes()?);
        Ok(result)
    }
}

impl<T1: FromBytes, T2: FromBytes, T3: FromBytes> FromBytes for (T1, T2, T3) {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        let (t3, remainder) = T3::from_bytes(remainder)?;
        Ok(((t1, t2, t3), remainder))
    }
}

impl<T1: ToBytes, T2: ToBytes, T3: ToBytes, T4: ToBytes> ToBytes for (T1, T2, T3, T4) {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        result.append(&mut self.2.to_bytes()?);
        result.append(&mut self.3.to_bytes()?);
        Ok(result)
    }
}

impl<T1: FromBytes, T2: FromBytes, T3: FromBytes, T4: FromBytes> FromBytes for (T1, T2, T3, T4) {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        let (t3, remainder) = T3::from_bytes(remainder)?;
        let (t4, remainder) = T4::from_bytes(remainder)?;
        Ok(((t1, t2, t3, t4), remainder))
    }
}

impl<T1: ToBytes, T2: ToBytes, T3: ToBytes, T4: ToBytes, T5: ToBytes> ToBytes
    for (T1, T2, T3, T4, T5)
{
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        result.append(&mut self.2.to_bytes()?);
        result.append(&mut self.3.to_bytes()?);
        result.append(&mut self.4.to_bytes()?);
        Ok(result)
    }
}

impl<T1: FromBytes, T2: FromBytes, T3: FromBytes, T4: FromBytes, T5: FromBytes> FromBytes
    for (T1, T2, T3, T4, T5)
{
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        let (t3, remainder) = T3::from_bytes(remainder)?;
        let (t4, remainder) = T4::from_bytes(remainder)?;
        let (t5, remainder) = T5::from_bytes(remainder)?;
        Ok(((t1, t2, t3, t4, t5), remainder))
    }
}

impl<T1: ToBytes, T2: ToBytes, T3: ToBytes, T4: ToBytes, T5: ToBytes, T6: ToBytes> ToBytes
    for (T1, T2, T3, T4, T5, T6)
{
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        result.append(&mut self.2.to_bytes()?);
        result.append(&mut self.3.to_bytes()?);
        result.append(&mut self.4.to_bytes()?);
        result.append(&mut self.5.to_bytes()?);
        Ok(result)
    }
}

impl<T1: FromBytes, T2: FromBytes, T3: FromBytes, T4: FromBytes, T5: FromBytes, T6: FromBytes>
    FromBytes for (T1, T2, T3, T4, T5, T6)
{
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        let (t3, remainder) = T3::from_bytes(remainder)?;
        let (t4, remainder) = T4::from_bytes(remainder)?;
        let (t5, remainder) = T5::from_bytes(remainder)?;
        let (t6, remainder) = T6::from_bytes(remainder)?;
        Ok(((t1, t2, t3, t4, t5, t6), remainder))
    }
}

impl<T1: ToBytes, T2: ToBytes, T3: ToBytes, T4: ToBytes, T5: ToBytes, T6: ToBytes, T7: ToBytes>
    ToBytes for (T1, T2, T3, T4, T5, T6, T7)
{
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        result.append(&mut self.2.to_bytes()?);
        result.append(&mut self.3.to_bytes()?);
        result.append(&mut self.4.to_bytes()?);
        result.append(&mut self.5.to_bytes()?);
        result.append(&mut self.6.to_bytes()?);
        Ok(result)
    }
}

impl<
        T1: FromBytes,
        T2: FromBytes,
        T3: FromBytes,
        T4: FromBytes,
        T5: FromBytes,
        T6: FromBytes,
        T7: FromBytes,
    > FromBytes for (T1, T2, T3, T4, T5, T6, T7)
{
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        let (t3, remainder) = T3::from_bytes(remainder)?;
        let (t4, remainder) = T4::from_bytes(remainder)?;
        let (t5, remainder) = T5::from_bytes(remainder)?;
        let (t6, remainder) = T6::from_bytes(remainder)?;
        let (t7, remainder) = T7::from_bytes(remainder)?;
        Ok(((t1, t2, t3, t4, t5, t6, t7), remainder))
    }
}

impl<
        T1: ToBytes,
        T2: ToBytes,
        T3: ToBytes,
        T4: ToBytes,
        T5: ToBytes,
        T6: ToBytes,
        T7: ToBytes,
        T8: ToBytes,
    > ToBytes for (T1, T2, T3, T4, T5, T6, T7, T8)
{
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        result.append(&mut self.2.to_bytes()?);
        result.append(&mut self.3.to_bytes()?);
        result.append(&mut self.4.to_bytes()?);
        result.append(&mut self.5.to_bytes()?);
        result.append(&mut self.6.to_bytes()?);
        result.append(&mut self.7.to_bytes()?);
        Ok(result)
    }
}

impl<
        T1: FromBytes,
        T2: FromBytes,
        T3: FromBytes,
        T4: FromBytes,
        T5: FromBytes,
        T6: FromBytes,
        T7: FromBytes,
        T8: FromBytes,
    > FromBytes for (T1, T2, T3, T4, T5, T6, T7, T8)
{
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        let (t3, remainder) = T3::from_bytes(remainder)?;
        let (t4, remainder) = T4::from_bytes(remainder)?;
        let (t5, remainder) = T5::from_bytes(remainder)?;
        let (t6, remainder) = T6::from_bytes(remainder)?;
        let (t7, remainder) = T7::from_bytes(remainder)?;
        let (t8, remainder) = T8::from_bytes(remainder)?;
        Ok(((t1, t2, t3, t4, t5, t6, t7, t8), remainder))
    }
}

impl<
        T1: ToBytes,
        T2: ToBytes,
        T3: ToBytes,
        T4: ToBytes,
        T5: ToBytes,
        T6: ToBytes,
        T7: ToBytes,
        T8: ToBytes,
        T9: ToBytes,
    > ToBytes for (T1, T2, T3, T4, T5, T6, T7, T8, T9)
{
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        result.append(&mut self.2.to_bytes()?);
        result.append(&mut self.3.to_bytes()?);
        result.append(&mut self.4.to_bytes()?);
        result.append(&mut self.5.to_bytes()?);
        result.append(&mut self.6.to_bytes()?);
        result.append(&mut self.7.to_bytes()?);
        result.append(&mut self.8.to_bytes()?);
        Ok(result)
    }
}

impl<
        T1: FromBytes,
        T2: FromBytes,
        T3: FromBytes,
        T4: FromBytes,
        T5: FromBytes,
        T6: FromBytes,
        T7: FromBytes,
        T8: FromBytes,
        T9: FromBytes,
    > FromBytes for (T1, T2, T3, T4, T5, T6, T7, T8, T9)
{
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        let (t3, remainder) = T3::from_bytes(remainder)?;
        let (t4, remainder) = T4::from_bytes(remainder)?;
        let (t5, remainder) = T5::from_bytes(remainder)?;
        let (t6, remainder) = T6::from_bytes(remainder)?;
        let (t7, remainder) = T7::from_bytes(remainder)?;
        let (t8, remainder) = T8::from_bytes(remainder)?;
        let (t9, remainder) = T9::from_bytes(remainder)?;
        Ok(((t1, t2, t3, t4, t5, t6, t7, t8, t9), remainder))
    }
}

impl<
        T1: ToBytes,
        T2: ToBytes,
        T3: ToBytes,
        T4: ToBytes,
        T5: ToBytes,
        T6: ToBytes,
        T7: ToBytes,
        T8: ToBytes,
        T9: ToBytes,
        T10: ToBytes,
    > ToBytes for (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)
{
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::new();
        result.append(&mut self.0.to_bytes()?);
        result.append(&mut self.1.to_bytes()?);
        result.append(&mut self.2.to_bytes()?);
        result.append(&mut self.3.to_bytes()?);
        result.append(&mut self.4.to_bytes()?);
        result.append(&mut self.5.to_bytes()?);
        result.append(&mut self.6.to_bytes()?);
        result.append(&mut self.7.to_bytes()?);
        result.append(&mut self.8.to_bytes()?);
        result.append(&mut self.9.to_bytes()?);
        Ok(result)
    }
}

impl<
        T1: FromBytes,
        T2: FromBytes,
        T3: FromBytes,
        T4: FromBytes,
        T5: FromBytes,
        T6: FromBytes,
        T7: FromBytes,
        T8: FromBytes,
        T9: FromBytes,
        T10: FromBytes,
    > FromBytes for (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)
{
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (t1, remainder) = T1::from_bytes(bytes)?;
        let (t2, remainder) = T2::from_bytes(remainder)?;
        let (t3, remainder) = T3::from_bytes(remainder)?;
        let (t4, remainder) = T4::from_bytes(remainder)?;
        let (t5, remainder) = T5::from_bytes(remainder)?;
        let (t6, remainder) = T6::from_bytes(remainder)?;
        let (t7, remainder) = T7::from_bytes(remainder)?;
        let (t8, remainder) = T8::from_bytes(remainder)?;
        let (t9, remainder) = T9::from_bytes(remainder)?;
        let (t10, remainder) = T10::from_bytes(remainder)?;
        Ok(((t1, t2, t3, t4, t5, t6, t7, t8, t9, t10), remainder))
    }
}

#[doc(hidden)]
/// Returns `true` if a we can serialize and then deserialize a value
pub fn test_serialization_roundtrip<T>(t: &T)
where
    T: ToBytes + FromBytes + PartialEq,
{
    let serialized = ToBytes::to_bytes(t).expect("Unable to serialize data");
    let deserialized = deserialize::<T>(serialized).expect("Unable to deserialize data");
    assert!(*t == deserialized)
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;

    use super::*;

    #[test]
    fn check_array_from_bytes_doesnt_leak() {
        thread_local!(static INSTANCE_COUNT: RefCell<usize> = RefCell::new(0));
        const MAX_INSTANCES: usize = 10;

        struct LeakChecker;

        impl FromBytes for LeakChecker {
            fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
                let instance_num = INSTANCE_COUNT.with(|count| *count.borrow());
                if instance_num >= MAX_INSTANCES {
                    Err(Error::FormattingError)
                } else {
                    INSTANCE_COUNT.with(|count| *count.borrow_mut() += 1);
                    Ok((LeakChecker, bytes))
                }
            }
        }

        impl Drop for LeakChecker {
            fn drop(&mut self) {
                INSTANCE_COUNT.with(|count| *count.borrow_mut() -= 1);
            }
        }

        // Check we can construct an array of `MAX_INSTANCES` of `LeakChecker`s.
        {
            let bytes = (MAX_INSTANCES as u32).to_bytes().unwrap();
            let _array = <[LeakChecker; MAX_INSTANCES]>::from_bytes(&bytes).unwrap();
            // Assert `INSTANCE_COUNT == MAX_INSTANCES`
            INSTANCE_COUNT.with(|count| assert_eq!(MAX_INSTANCES, *count.borrow()));
        }

        // Assert the `INSTANCE_COUNT` has dropped to zero again.
        INSTANCE_COUNT.with(|count| assert_eq!(0, *count.borrow()));

        // Try to construct an array of `LeakChecker`s where the `MAX_INSTANCES + 1`th instance
        // returns an error.
        let bytes = (MAX_INSTANCES as u32 + 1).to_bytes().unwrap();
        let result = <[LeakChecker; MAX_INSTANCES + 1]>::from_bytes(&bytes);
        assert!(result.is_err());

        // Assert the `INSTANCE_COUNT` has dropped to zero again.
        INSTANCE_COUNT.with(|count| assert_eq!(0, *count.borrow()));
    }
}

#[allow(clippy::unnecessary_operation)]
#[cfg(test)]
mod proptests {
    use proptest::{collection::vec, prelude::*};

    use crate::{bytesrepr, gens::*};

    proptest! {
        #[test]
        fn test_bool(u in any::<bool>()) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_u8(u in any::<u8>()) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_u32(u in any::<u32>()) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_i32(u in any::<i32>()) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_u64(u in any::<u64>()) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_i64(u in any::<i64>()) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_u8_slice_32(s in u8_slice_32()) {
            bytesrepr::test_serialization_roundtrip(&s)
        }

        #[test]
        fn test_vec_u8(u in vec(any::<u8>(), 1..100)) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_vec_i32(u in vec(any::<i32>(), 1..100)) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_vec_vec_u8(u in vec(vec(any::<u8>(), 1..100), 10)) {
            bytesrepr::test_serialization_roundtrip(&u)
        }

        #[test]
        fn test_uref_map(m in named_keys_arb(20)) {
            bytesrepr::test_serialization_roundtrip(&m)
        }

        #[test]
        fn test_array_u8_32(arr in any::<[u8; 32]>()) {
            bytesrepr::test_serialization_roundtrip(&arr)
        }

        #[test]
        fn test_string(s in "\\PC*") {
            bytesrepr::test_serialization_roundtrip(&s)
        }

        #[test]
        fn test_option(o in proptest::option::of(key_arb())) {
            bytesrepr::test_serialization_roundtrip(&o)
        }

        #[test]
        fn test_unit(unit in Just(())) {
            bytesrepr::test_serialization_roundtrip(&unit)
        }

        #[test]
        fn test_u128_serialization(u in u128_arb()) {
            bytesrepr::test_serialization_roundtrip(&u);
        }

        #[test]
        fn test_u256_serialization(u in u256_arb()) {
            bytesrepr::test_serialization_roundtrip(&u);
        }

        #[test]
        fn test_u512_serialization(u in u512_arb()) {
            bytesrepr::test_serialization_roundtrip(&u);
        }

        #[test]
        fn test_key_serialization(key in key_arb()) {
            bytesrepr::test_serialization_roundtrip(&key);
        }

        #[test]
        fn test_cl_value_serialization(cl_value in cl_value_arb()) {
            bytesrepr::test_serialization_roundtrip(&cl_value);
        }

        #[test]
        fn test_access_rights(access_right in access_rights_arb()) {
            bytesrepr::test_serialization_roundtrip(&access_right)
        }

        #[test]
        fn test_uref(uref in uref_arb()) {
            bytesrepr::test_serialization_roundtrip(&uref);
        }

        #[test]
        fn test_public_key(pk in public_key_arb()) {
            bytesrepr::test_serialization_roundtrip(&pk)
        }

        #[test]
        fn test_result(result in result_arb()) {
            bytesrepr::test_serialization_roundtrip(&result)
        }

        #[test]
        fn test_phase_serialization(phase in phase_arb()) {
            bytesrepr::test_serialization_roundtrip(&phase)
        }

        #[test]
        fn test_protocol_version(protocol_version in protocol_version_arb()) {
            bytesrepr::test_serialization_roundtrip(&protocol_version)
        }

        #[test]
        fn test_sem_ver(sem_ver in sem_ver_arb()) {
            bytesrepr::test_serialization_roundtrip(&sem_ver)
        }

        #[test]
        fn test_tuple1(t in (any::<u8>(),)) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple2(t in (any::<u8>(),any::<u32>())) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple3(t in (any::<u8>(),any::<u32>(),any::<i32>())) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple4(t in (any::<u8>(),any::<u32>(),any::<i32>(), any::<u64>())) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple5(t in (any::<u8>(),any::<u32>(),any::<i32>(), any::<u64>(), u8_slice_32())) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple6(t in (any::<u8>(),any::<u32>(),any::<i32>(), any::<u64>(), u8_slice_32(), any::<[u8; 32]>())) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple7(t in (any::<u8>(),any::<u32>(),any::<i32>(), any::<u64>(), u8_slice_32(), any::<[u8; 32]>(), Just(()))) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple8(t in (any::<u8>(),any::<u32>(),any::<i32>(), any::<u64>(), u8_slice_32(), any::<[u8; 32]>(), Just(()), "a")) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple9(t in (any::<u8>(),any::<u32>(),any::<i32>(), any::<u64>(), u8_slice_32(), any::<[u8; 32]>(), Just(()), "a", uref_arb())) {
            bytesrepr::test_serialization_roundtrip(&t)
        }

        #[test]
        fn test_tuple10(t in (any::<u8>(),any::<u32>(),any::<i32>(), any::<u64>(), u8_slice_32(), any::<[u8; 32]>(), Just(()), "a", uref_arb(), public_key_arb())) {
            bytesrepr::test_serialization_roundtrip(&t)
        }
    }
}
