use std::convert::TryFrom;

use contract_ffi::value::{Value, U128, U256, U512};

use crate::engine_server::{mappings::ParsingError, state::BigInt as ProtobufBigInt};

impl TryFrom<ProtobufBigInt> for Value {
    type Error = ParsingError;

    fn try_from(pb_big_int: ProtobufBigInt) -> Result<Value, Self::Error> {
        match pb_big_int.get_bit_width() {
            128 => Ok(U128::try_from(pb_big_int)?.into()),
            256 => Ok(U256::try_from(pb_big_int)?.into()),
            512 => Ok(U512::try_from(pb_big_int)?.into()),
            other => Err(invalid_bit_width(other)),
        }
    }
}

fn invalid_bit_width(bit_width: u32) -> ParsingError {
    ParsingError(format!(
        "Protobuf BigInt bit width of {} is invalid",
        bit_width
    ))
}

macro_rules! protobuf_conversions_for_uint {
    ($type:ty, $bit_width:literal) => {
        impl From<$type> for ProtobufBigInt {
            fn from(value: $type) -> Self {
                let mut pb_big_int = ProtobufBigInt::new();
                pb_big_int.set_value(format!("{}", value));
                pb_big_int.set_bit_width($bit_width);
                pb_big_int
            }
        }

        impl TryFrom<ProtobufBigInt> for $type {
            type Error = ParsingError;
            fn try_from(pb_big_int: ProtobufBigInt) -> Result<Self, Self::Error> {
                let value = pb_big_int.get_value();
                match pb_big_int.get_bit_width() {
                    $bit_width => <$type>::from_dec_str(value)
                        .map_err(|error| ParsingError(format!("{:?}", error))),
                    other => Err(invalid_bit_width(other)),
                }
            }
        }
    };
}

protobuf_conversions_for_uint!(U128, 128);
protobuf_conversions_for_uint!(U256, 256);
protobuf_conversions_for_uint!(U512, 512);

#[cfg(test)]
mod tests {
    use std::fmt::Debug;

    use proptest::proptest;

    use contract_ffi::gens;

    use super::*;
    use crate::engine_server::mappings::test_utils;

    proptest! {
        #[test]
        fn u128_round_trip(u128 in gens::u128_arb()) {
            test_utils::protobuf_round_trip::<U128, ProtobufBigInt>(u128);
        }

        #[test]
        fn u256_round_trip(u256 in gens::u256_arb()) {
            test_utils::protobuf_round_trip::<U256, ProtobufBigInt>(u256);
        }

        #[test]
        fn u512_round_trip(u512 in gens::u512_arb()) {
            test_utils::protobuf_round_trip::<U512, ProtobufBigInt>(u512);
        }
    }

    fn try_with_bad_value<T>(value: T)
    where
        T: Debug + Into<ProtobufBigInt> + TryFrom<ProtobufBigInt>,
        <T as TryFrom<ProtobufBigInt>>::Error: Debug + Into<ParsingError>,
    {
        let expected_error = ParsingError("InvalidCharacter".to_string());

        let mut invalid_pb_big_int = value.into();
        invalid_pb_big_int.set_value("a".to_string());

        assert_eq!(
            expected_error,
            T::try_from(invalid_pb_big_int.clone()).unwrap_err().into()
        );
        assert_eq!(
            expected_error,
            Value::try_from(invalid_pb_big_int.clone()).unwrap_err()
        );
    }

    fn try_with_invalid_bit_width<T>(value: T)
    where
        T: Debug + Into<ProtobufBigInt> + TryFrom<ProtobufBigInt>,
        <T as TryFrom<ProtobufBigInt>>::Error: Debug + Into<ParsingError>,
    {
        let bit_width = 127;
        let expected_error = invalid_bit_width(bit_width);

        let mut invalid_pb_big_int = value.into();
        invalid_pb_big_int.set_bit_width(bit_width);

        assert_eq!(
            expected_error,
            T::try_from(invalid_pb_big_int.clone()).unwrap_err().into()
        );
        assert_eq!(
            expected_error,
            Value::try_from(invalid_pb_big_int.clone()).unwrap_err()
        );
    }

    #[test]
    fn should_fail_to_parse() {
        try_with_bad_value(U128::one());
        try_with_bad_value(U256::one());
        try_with_bad_value(U512::one());

        try_with_invalid_bit_width(U128::one());
        try_with_invalid_bit_width(U256::one());
        try_with_invalid_bit_width(U512::one());
    }
}
