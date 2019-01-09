extern crate byteorder;

use super::serde::de::{
    self, DeserializeSeed, EnumAccess, MapAccess, SeqAccess, VariantAccess, Visitor,
};
use byteorder::{ByteOrder, LittleEndian};
use core::str::from_utf8;

use super::alloc::string::String;
use super::ser::Error;

pub struct Deserializer<'de> {
    input: &'de [u8],
}

impl<'de> Deserializer<'de> {
    pub fn from_bytes(input: &'de [u8]) -> Self {
        Deserializer { input }
    }

    pub fn safe_split_at(bytes: &[u8], n: usize) -> Result<(&[u8], &[u8]), Error> {
        if n > bytes.len() {
            Err(Error::EarlyEndOfStream)
        } else {
            Ok(bytes.split_at(n))
        }
    }

    pub fn take_bytes(&mut self, n: usize) -> Result<&[u8], Error> {
        let (bytes, rem) = Deserializer::safe_split_at(self.input, n)?;
        self.input = rem;
        Ok(bytes)
    }

    pub fn take_u32(&mut self) -> Result<u32, Error> {
        let bytes = self.take_bytes(4)?;
        Ok(LittleEndian::read_u32(bytes))
    }

    pub fn take_sized_bytes(&mut self) -> Result<&[u8], Error> {
        let size = self.take_u32()?;
        self.take_bytes(size as usize)
    }
}

impl<'de, 'a> de::Deserializer<'de> for &'a mut Deserializer<'de> {
    type Error = Error;

    fn deserialize_any<V: Visitor<'de>>(self, _visitor: V) -> Result<V::Value, Self::Error> {
        //binary format is not self-describing, so cannot use deserialize_any
        Err(Error::FormattingError)
    }

    fn deserialize_bool<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let byte = self.take_bytes(1)?;
        visitor.visit_bool(byte[0] == 1)
    }

    fn deserialize_i8<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let byte = self.take_bytes(1)?;
        visitor.visit_i8(byte[0] as i8)
    }

    fn deserialize_i16<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(2)?;
        let v = LittleEndian::read_i16(bytes);
        visitor.visit_i16(v)
    }

    fn deserialize_i32<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(4)?;
        let v = LittleEndian::read_i32(bytes);
        visitor.visit_i32(v)
    }

    fn deserialize_i64<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(8)?;
        let v = LittleEndian::read_i64(bytes);
        visitor.visit_i64(v)
    }

    fn deserialize_u8<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let byte = self.take_bytes(1)?;
        visitor.visit_u8(byte[0])
    }

    fn deserialize_u16<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(2)?;
        let v = LittleEndian::read_u16(bytes);
        visitor.visit_u16(v)
    }

    fn deserialize_u32<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(4)?;
        let v = LittleEndian::read_u32(bytes);
        visitor.visit_u32(v)
    }

    fn deserialize_u64<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(8)?;
        let v = LittleEndian::read_u64(bytes);
        visitor.visit_u64(v)
    }

    fn deserialize_f32<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(4)?;
        let v = LittleEndian::read_f32(bytes);
        visitor.visit_f32(v)
    }

    fn deserialize_f64<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(8)?;
        let v = LittleEndian::read_f64(bytes);
        visitor.visit_f64(v)
    }

    fn deserialize_char<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_bytes(4)?;
        let v = from_utf8(bytes)
            .map_err(|_| Error::FormattingError)
            .and_then(|s| s.chars().next().ok_or(Error::FormattingError))?;
        visitor.visit_char(v)
    }

    fn deserialize_str<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_sized_bytes()?;
        let s = from_utf8(bytes).map_err(|_| Error::FormattingError)?;
        visitor.visit_str(s)
    }

    fn deserialize_string<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_sized_bytes()?;
        let s = String::from_utf8(bytes.to_vec()).map_err(|_| Error::FormattingError)?;
        visitor.visit_string(s)
    }

    fn deserialize_bytes<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_sized_bytes()?;
        visitor.visit_bytes(bytes)
    }

    fn deserialize_byte_buf<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let bytes = self.take_sized_bytes()?;
        visitor.visit_byte_buf(bytes.to_vec())
    }

    fn deserialize_option<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let byte = self.take_bytes(1)?;
        if byte[0] == 0 {
            visitor.visit_none()
        } else {
            visitor.visit_some(self)
        }
    }

    fn deserialize_unit<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        visitor.visit_unit()
    }

    fn deserialize_unit_struct<V: Visitor<'de>>(
        self,
        _name: &'static str,
        visitor: V,
    ) -> Result<V::Value, Self::Error> {
        self.deserialize_unit(visitor)
    }

    fn deserialize_newtype_struct<V: Visitor<'de>>(
        self,
        _name: &'static str,
        visitor: V,
    ) -> Result<V::Value, Self::Error> {
        visitor.visit_newtype_struct(self)
    }

    fn deserialize_seq<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let size = self.take_u32()?;
        self.deserialize_tuple(size as usize, visitor)
    }

    fn deserialize_tuple<V: Visitor<'de>>(
        self,
        len: usize,
        visitor: V,
    ) -> Result<V::Value, Self::Error> {
        let accessor = TakeN::new(self, len);
        visitor.visit_seq(accessor)
    }

    fn deserialize_tuple_struct<V: Visitor<'de>>(
        self,
        _name: &'static str,
        len: usize,
        visitor: V,
    ) -> Result<V::Value, Self::Error> {
        self.deserialize_tuple(len, visitor)
    }

    fn deserialize_map<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        let size = self.take_u32()?;
        let accessor = TakeN::new(self, size as usize);
        visitor.visit_map(accessor)
    }

    fn deserialize_struct<V: Visitor<'de>>(
        self,
        _name: &'static str,
        _fields: &'static [&'static str],
        visitor: V,
    ) -> Result<V::Value, Self::Error> {
        self.deserialize_map(visitor)
    }

    fn deserialize_enum<V: Visitor<'de>>(
        self,
        _name: &'static str,
        _variants: &'static [&'static str],
        visitor: V,
    ) -> Result<V::Value, Self::Error> {
        //size doesn't matter here because it is never used in enum-related methods
        visitor.visit_enum(TakeN::new(self, 0))
    }

    fn deserialize_identifier<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        self.deserialize_str(visitor)
    }

    fn deserialize_ignored_any<V: Visitor<'de>>(self, visitor: V) -> Result<V::Value, Self::Error> {
        visitor.visit_unit()
    }
}

struct TakeN<'a, 'de: 'a> {
    de: &'a mut Deserializer<'de>,
    n: usize,
}

impl<'a, 'de> TakeN<'a, 'de> {
    fn new(de: &'a mut Deserializer<'de>, n: usize) -> Self {
        TakeN { de, n }
    }
}

impl<'de, 'a> SeqAccess<'de> for TakeN<'a, 'de> {
    type Error = Error;

    fn next_element_seed<T>(&mut self, seed: T) -> Result<Option<T::Value>, Self::Error>
    where
        T: DeserializeSeed<'de>,
    {
        if self.n == 0 {
            Ok(None)
        } else {
            self.n -= 1;
            seed.deserialize(&mut *self.de).map(Some)
        }
    }
}

impl<'de, 'a> MapAccess<'de> for TakeN<'a, 'de> {
    type Error = Error;

    fn next_key_seed<K>(&mut self, seed: K) -> Result<Option<K::Value>, Self::Error>
    where
        K: DeserializeSeed<'de>,
    {
        if self.n == 0 {
            return Ok(None);
        } else {
            self.n -= 1;
            seed.deserialize(&mut *self.de).map(Some)
        }
    }

    fn next_value_seed<V>(&mut self, seed: V) -> Result<V::Value, Self::Error>
    where
        V: DeserializeSeed<'de>,
    {
        seed.deserialize(&mut *self.de)
    }
}

impl<'de, 'a> EnumAccess<'de> for TakeN<'a, 'de> {
    type Error = Error;
    type Variant = Self;

    fn variant_seed<V>(self, seed: V) -> Result<(V::Value, Self::Variant), Self::Error>
    where
        V: DeserializeSeed<'de>,
    {
        let val = seed.deserialize(&mut *self.de)?;
        Ok((val, self))
    }
}

impl<'de, 'a> VariantAccess<'de> for TakeN<'a, 'de> {
    type Error = Error;

    fn unit_variant(self) -> Result<(), Self::Error> {
        Ok(())
    }

    fn newtype_variant_seed<T>(self, seed: T) -> Result<T::Value, Self::Error>
    where
        T: DeserializeSeed<'de>,
    {
        seed.deserialize(self.de)
    }

    fn tuple_variant<V>(self, len: usize, visitor: V) -> Result<V::Value, Self::Error>
    where
        V: Visitor<'de>,
    {
        de::Deserializer::deserialize_tuple(self.de, len, visitor)
    }

    fn struct_variant<V>(
        self,
        _fields: &'static [&'static str],
        visitor: V,
    ) -> Result<V::Value, Self::Error>
    where
        V: Visitor<'de>,
    {
        de::Deserializer::deserialize_map(self.de, visitor)
    }
}
