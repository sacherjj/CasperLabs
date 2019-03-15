use super::alloc::collections::BTreeMap;
use super::alloc::string::String;
use super::alloc::vec::Vec;
use core::mem::{size_of, MaybeUninit};

const I32_SIZE: usize = size_of::<i32>();
const U32_SIZE: usize = size_of::<u32>();

const N32: usize = 32;
const N256: usize = 256;

pub trait ToBytes {
    fn to_bytes(&self) -> Vec<u8>;
}

pub trait FromBytes: Sized {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error>;
}

#[derive(Debug, Fail, PartialEq, Eq, Clone)]
pub enum Error {
    #[fail(display = "Deserialization error: early end of stream")]
    EarlyEndOfStream,

    #[fail(display = "Deserialization error: formatting error")]
    FormattingError,

    #[fail(display = "Deserialization error: left-over bytes")]
    LeftOverBytes,
}

pub fn deserialize<T: FromBytes>(bytes: &[u8]) -> Result<T, Error> {
    let (t, rem): (T, &[u8]) = FromBytes::from_bytes(bytes)?;
    if rem.is_empty() {
        Ok(t)
    } else {
        Err(Error::LeftOverBytes)
    }
}

fn safe_split_at(bytes: &[u8], n: usize) -> Result<(&[u8], &[u8]), Error> {
    if n > bytes.len() {
        Err(Error::EarlyEndOfStream)
    } else {
        Ok(bytes.split_at(n))
    }
}

impl ToBytes for u8 {
    fn to_bytes(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(1);
        result.push(*self);
        result
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
    fn to_bytes(&self) -> Vec<u8> {
        self.to_le_bytes().to_vec()
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
    fn to_bytes(&self) -> Vec<u8> {
        self.to_le_bytes().to_vec()
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
    fn to_bytes(&self) -> Vec<u8> {
        self.to_le_bytes().to_vec()
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

impl FromBytes for Vec<u8> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<u8> = Vec::with_capacity(size as usize);
        for _ in 0..size {
            let (t, rem): (u8, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for Vec<u8> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(U32_SIZE + size as usize);
        result.extend(size.to_bytes());
        result.extend(self);
        result
    }
}

impl FromBytes for Vec<i32> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<i32> = Vec::with_capacity(I32_SIZE * size as usize);
        for _ in 0..size {
            let (t, rem): (i32, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl<T: ToBytes> ToBytes for Option<T> {
    fn to_bytes(&self) -> Vec<u8> {
        match self {
            Some(v) => {
                let mut value = v.to_bytes();
                let mut result = Vec::with_capacity(U32_SIZE + value.len());
                result.append(&mut 1u32.to_bytes());
                result.append(&mut value);
                result
            }
            // In the case of None there is no value to serialize, but we still
            // need to write out a tag to indicate which variant we are using
            None => 0u32.to_bytes(),
        }
    }
}

impl<T: FromBytes> FromBytes for Option<T> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (tag, rem): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
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
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(U32_SIZE + (I32_SIZE * size as usize));
        result.extend(size.to_bytes());
        result.extend(self.iter().flat_map(ToBytes::to_bytes));
        result
    }
}

impl FromBytes for Vec<Vec<u8>> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<Vec<u8>> = Vec::with_capacity(size as usize);
        for _ in 0..size {
            let (v, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(v);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for Vec<Vec<u8>> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(U32_SIZE + size as usize);
        result.extend_from_slice(&size.to_bytes());
        for n in 0..size {
            result.extend_from_slice(&self[n as usize].to_bytes());
        }
        result
    }
}

impl FromBytes for Vec<String> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<String> = Vec::with_capacity(size as usize);
        for _ in 0..size {
            let (s, rem): (String, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(s);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for Vec<String> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result = Vec::with_capacity(U32_SIZE);
        result.extend(size.to_bytes());
        result.extend(self.iter().flat_map(ToBytes::to_bytes));
        result
    }
}

impl ToBytes for [u8; N32] {
    fn to_bytes(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(U32_SIZE + N32);
        result.extend((N32 as u32).to_bytes());
        result.extend(self);
        result
    }
}

impl FromBytes for [u8; N32] {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bytes, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(bytes)?;
        if bytes.len() != N32 {
            return Err(Error::FormattingError);
        };
        let mut result = [0u8; N32];
        result.copy_from_slice(&bytes);
        Ok((result, rem))
    }
}

impl<T: ToBytes> ToBytes for [T; N256] {
    fn to_bytes(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(U32_SIZE + (self.len() * size_of::<T>()));
        result.extend((N256 as u32).to_bytes());
        result.extend(self.iter().flat_map(ToBytes::to_bytes));
        result
    }
}

impl<T: FromBytes> FromBytes for [T; N256] {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, mut stream): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        if size != N256 as u32 {
            return Err(Error::FormattingError);
        }
        let mut result: MaybeUninit<[T; N256]> = MaybeUninit::uninitialized();
        let result_ptr = result.as_mut_ptr() as *mut T;
        unsafe {
            for i in 0..N256 {
                let (t, rem): (T, &[u8]) = FromBytes::from_bytes(stream)?;
                result_ptr.add(i).write(t);
                stream = rem;
            }
            Ok((result.into_initialized(), stream))
        }
    }
}

impl ToBytes for String {
    fn to_bytes(&self) -> Vec<u8> {
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
    fn to_bytes(&self) -> Vec<u8> {
        Vec::new()
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
    fn to_bytes(&self) -> Vec<u8> {
        let num_keys = self.len() as u32;
        let bytes = self.iter().flat_map(|(k, v)| {
            let mut b = k.to_bytes();
            b.append(&mut v.to_bytes());
            b
        });
        let mut result = Vec::with_capacity(U32_SIZE + bytes.size_hint().0);
        result.append(&mut num_keys.to_bytes());
        result.extend(bytes);
        result
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
    fn to_bytes(&self) -> Vec<u8> {
        let bytes = self.as_bytes();
        let size = self.len();
        let mut result = Vec::with_capacity(U32_SIZE + size);
        result.extend((size as u32).to_bytes());
        result.extend(bytes);
        result
    }
}
