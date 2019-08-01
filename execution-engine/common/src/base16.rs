use crate::alloc::prelude::String;
use alloc::vec::Vec;
use core::num::ParseIntError;
use core::str;

/// Encodes a slice of bytes in base16 form in lower case
pub fn encode_lower(input: &[u8]) -> String {
    input.iter().map(|b| format!("{:02x}", b)).collect()
}

#[derive(Debug, Fail, PartialEq)]
pub enum Error {
    #[fail(display = "Length of input string should be a multiply of 2")]
    InvalidLengthError,
    #[fail(display = "{}", _0)]
    DecodeError(str::Utf8Error),
    #[fail(display = "{}", _0)]
    ParseError(ParseIntError),
}

/// Decodes a slice of bytes in base16 form
pub fn decode_lower(input: &str) -> Result<Vec<u8>, Error> {
    if input.is_empty() {
        Ok(Vec::new())
    } else if input.len() % 2 != 0 {
        Err(Error::InvalidLengthError)
    } else {
        input
            .as_bytes()
            .chunks(2)
            .map(|ch| {
                str::from_utf8(&ch)
                    .map_err(Error::DecodeError)
                    .and_then(|res| u8::from_str_radix(&res, 16).map_err(Error::ParseError))
            })
            .collect()
    }
}

#[test]
fn test_encode_lower() {
    assert_eq!(encode_lower(&[1, 2, 3, 254, 255]), "010203feff");
    assert_eq!(encode_lower(&[]), "");
}

#[test]
fn test_decode_lower() {
    assert!(decode_lower("Hello world!").is_err());
    assert_eq!(
        decode_lower("010203feff").expect("should decode"),
        &[0x01, 0x02, 0x03, 0xfe, 0xff]
    );
    // invalid length
    assert!(decode_lower("010").is_err());
    assert_eq!(decode_lower("").expect("should decode empty"), vec![]);
    // invalid characters
    assert!(decode_lower("\u{012345}deadbeef").is_err());
}
