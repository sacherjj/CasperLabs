extern crate byteorder;

use super::serde::de;
use super::serde::ser::{self, Serialize};
use byteorder::{ByteOrder, LittleEndian};
use core::fmt::{self, Display, Formatter};

use super::alloc::vec::Vec;

#[derive(Debug)]
pub enum Error {
    EarlyEndOfStream,
    FormattingError,
    LeftOverBytes,
}

impl Display for Error {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl ser::Error for Error {
    fn custom<T: Display>(_msg: T) -> Self {
        Error::FormattingError
    }
}

impl de::Error for Error {
    fn custom<T: Display>(_msg: T) -> Self {
        Error::FormattingError
    }
}

pub struct Serializer {
    output: Vec<u8>,
}

impl Serializer {
    fn u32_bytes(v: u32) -> [u8; 4] {
        let mut buf = [0u8; 4];
        LittleEndian::write_u32(&mut buf, v);
        buf
    }
}

pub fn to_bytes<T>(t: &T) -> Result<Vec<u8>, Error>
where
    T: Serialize,
{
    let mut s = Serializer { output: Vec::new() };
    t.serialize(&mut s)?;
    Ok(s.output)
}

impl<'a> ser::Serializer for &'a mut Serializer {
    type Ok = ();
    type Error = Error;

    type SerializeSeq = Self;
    type SerializeTuple = Self;
    type SerializeTupleStruct = Self;
    type SerializeTupleVariant = Self;
    type SerializeMap = Self;
    type SerializeStruct = Self;
    type SerializeStructVariant = Self;

    fn serialize_bool(self, v: bool) -> Result<Self::Ok, Self::Error> {
        if v {
            self.output.push(1u8);
        } else {
            self.output.push(0u8);
        }

        Ok(())
    }

    fn serialize_i8(self, v: i8) -> Result<Self::Ok, Self::Error> {
        self.output.push(v as u8);

        Ok(())
    }

    fn serialize_i16(self, v: i16) -> Result<Self::Ok, Self::Error> {
        let mut buf = [0u8; 2];
        LittleEndian::write_i16(&mut buf, v);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_i32(self, v: i32) -> Result<Self::Ok, Self::Error> {
        let mut buf = [0u8; 4];
        LittleEndian::write_i32(&mut buf, v);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_i64(self, v: i64) -> Result<Self::Ok, Self::Error> {
        let mut buf = [0u8; 8];
        LittleEndian::write_i64(&mut buf, v);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_u8(self, v: u8) -> Result<Self::Ok, Self::Error> {
        self.output.push(v);

        Ok(())
    }

    fn serialize_u16(self, v: u16) -> Result<Self::Ok, Self::Error> {
        let mut buf = [0u8; 2];
        LittleEndian::write_u16(&mut buf, v);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_u32(self, v: u32) -> Result<Self::Ok, Self::Error> {
        let buf = Serializer::u32_bytes(v);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_u64(self, v: u64) -> Result<Self::Ok, Self::Error> {
        let mut buf = [0u8; 8];
        LittleEndian::write_u64(&mut buf, v);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_f32(self, v: f32) -> Result<Self::Ok, Self::Error> {
        let mut buf = [0u8; 4];
        LittleEndian::write_f32(&mut buf, v);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_f64(self, v: f64) -> Result<Self::Ok, Self::Error> {
        let mut buf = [0u8; 8];
        LittleEndian::write_f64(&mut buf, v);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_char(self, v: char) -> Result<Self::Ok, Self::Error> {
        let mut buf = [0u8; 4];
        let _ = v.encode_utf8(&mut buf);
        self.output.extend_from_slice(&buf);

        Ok(())
    }

    fn serialize_str(self, v: &str) -> Result<Self::Ok, Self::Error> {
        let bytes = v.as_bytes();
        self.serialize_bytes(bytes)
    }

    fn serialize_bytes(self, v: &[u8]) -> Result<Self::Ok, Self::Error> {
        let size = v.len();
        let size_bytes = Serializer::u32_bytes(size as u32);
        self.output.extend_from_slice(&size_bytes);
        self.output.extend_from_slice(v);

        Ok(())
    }

    fn serialize_none(self) -> Result<Self::Ok, Self::Error> {
        let bytes = [0u8; 1];
        self.output.extend_from_slice(&bytes);

        Ok(())
    }

    fn serialize_some<T>(self, value: &T) -> Result<Self::Ok, Self::Error>
    where
        T: ?Sized + Serialize,
    {
        let size_bytes = [1u8; 1];
        self.output.extend_from_slice(&size_bytes);

        value.serialize(self)
    }

    fn serialize_unit(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }

    fn serialize_unit_struct(self, _name: &'static str) -> Result<Self::Ok, Self::Error> {
        self.serialize_unit()
    }

    fn serialize_unit_variant(
        self,
        _name: &'static str,
        variant_index: u32,
        _variant: &'static str,
    ) -> Result<Self::Ok, Self::Error> {
        self.serialize_u32(variant_index)
    }

    fn serialize_newtype_struct<T>(
        self,
        _name: &'static str,
        value: &T,
    ) -> Result<Self::Ok, Self::Error>
    where
        T: ?Sized + Serialize,
    {
        value.serialize(self)
    }

    fn serialize_newtype_variant<T>(
        self,
        name: &'static str,
        variant_index: u32,
        variant: &'static str,
        value: &T,
    ) -> Result<Self::Ok, Self::Error>
    where
        T: ?Sized + Serialize,
    {
        self.serialize_unit_variant(name, variant_index, variant)
            .and_then(|_| value.serialize(self))
    }

    fn serialize_seq(self, len: Option<usize>) -> Result<Self::SerializeSeq, Self::Error> {
        if let Some(n) = len {
            let size = n as u32;
            let size_bytes = Serializer::u32_bytes(size);
            self.output.extend_from_slice(&size_bytes);
        }

        Ok(self)
    }

    fn serialize_tuple(self, _len: usize) -> Result<Self::SerializeTuple, Self::Error> {
        self.serialize_seq(None)
    }

    fn serialize_tuple_struct(
        self,
        _name: &'static str,
        len: usize,
    ) -> Result<Self::SerializeTupleStruct, Self::Error> {
        self.serialize_seq(Some(len))
    }

    fn serialize_tuple_variant(
        self,
        _name: &'static str,
        variant_index: u32,
        _variant: &'static str,
        len: usize,
    ) -> Result<Self::SerializeTupleVariant, Self::Error> {
        match self.serialize_u32(variant_index) {
            Ok(_) => self.serialize_seq(Some(len)),
            Err(e) => Err(e),
        }
    }

    fn serialize_map(self, len: Option<usize>) -> Result<Self::SerializeMap, Self::Error> {
        self.serialize_seq(len)
    }

    fn serialize_struct(
        self,
        _name: &'static str,
        len: usize,
    ) -> Result<Self::SerializeStruct, Self::Error> {
        self.serialize_seq(Some(len))
    }

    fn serialize_struct_variant(
        self,
        _name: &'static str,
        variant_index: u32,
        _variant: &'static str,
        len: usize,
    ) -> Result<Self::SerializeStructVariant, Self::Error> {
        match self.serialize_u32(variant_index) {
            Ok(_) => self.serialize_seq(Some(len)),
            Err(e) => Err(e),
        }
    }
}

impl<'a> ser::SerializeSeq for &'a mut Serializer {
    type Ok = ();
    type Error = Error;

    fn serialize_element<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + Serialize,
    {
        match value.serialize(&mut **self) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FormattingError),
        }
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl<'a> ser::SerializeTuple for &'a mut Serializer {
    type Ok = ();
    type Error = Error;

    fn serialize_element<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + Serialize,
    {
        match value.serialize(&mut **self) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FormattingError),
        }
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl<'a> ser::SerializeTupleStruct for &'a mut Serializer {
    type Ok = ();
    type Error = Error;

    fn serialize_field<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + Serialize,
    {
        match value.serialize(&mut **self) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FormattingError),
        }
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl<'a> ser::SerializeTupleVariant for &'a mut Serializer {
    type Ok = ();
    type Error = Error;

    fn serialize_field<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + Serialize,
    {
        match value.serialize(&mut **self) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FormattingError),
        }
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl<'a> ser::SerializeStruct for &'a mut Serializer {
    type Ok = ();
    type Error = Error;

    fn serialize_field<T>(&mut self, key: &'static str, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + Serialize,
    {
        let k_ser = key.serialize(&mut **self);
        let v_ser = value.serialize(&mut **self);

        match k_ser.and(v_ser) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FormattingError),
        }
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl<'a> ser::SerializeStructVariant for &'a mut Serializer {
    type Ok = ();
    type Error = Error;

    fn serialize_field<T>(&mut self, key: &'static str, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + Serialize,
    {
        let k_ser = key.serialize(&mut **self);
        let v_ser = value.serialize(&mut **self);

        match k_ser.and(v_ser) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FormattingError),
        }
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl<'a> ser::SerializeMap for &'a mut Serializer {
    type Ok = ();
    type Error = Error;

    fn serialize_key<T>(&mut self, key: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + Serialize,
    {
        match key.serialize(&mut **self) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FormattingError),
        }
    }

    fn serialize_value<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + Serialize,
    {
        match value.serialize(&mut **self) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FormattingError),
        }
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}
