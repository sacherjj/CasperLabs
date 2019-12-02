use std::convert::{TryFrom, TryInto};

use contract_ffi::value::CLType;

use crate::engine_server::{
    mappings::ParsingError,
    state::{self, CLType_Simple, CLType_oneof_variants},
};

impl From<CLType> for state::CLType {
    fn from(cl_type: CLType) -> Self {
        let mut pb_type = state::CLType::new();
        match cl_type {
            CLType::Bool => pb_type.set_simple_type(state::CLType_Simple::BOOL),
            CLType::I32 => pb_type.set_simple_type(state::CLType_Simple::I32),
            CLType::I64 => pb_type.set_simple_type(state::CLType_Simple::I64),
            CLType::U8 => pb_type.set_simple_type(state::CLType_Simple::U8),
            CLType::U32 => pb_type.set_simple_type(state::CLType_Simple::U32),
            CLType::U64 => pb_type.set_simple_type(state::CLType_Simple::U64),
            CLType::U128 => pb_type.set_simple_type(state::CLType_Simple::U128),
            CLType::U256 => pb_type.set_simple_type(state::CLType_Simple::U256),
            CLType::U512 => pb_type.set_simple_type(state::CLType_Simple::U512),
            CLType::Unit => pb_type.set_simple_type(state::CLType_Simple::UNIT),
            CLType::String => pb_type.set_simple_type(state::CLType_Simple::STRING),
            CLType::Key => pb_type.set_simple_type(state::CLType_Simple::KEY),
            CLType::URef => pb_type.set_simple_type(state::CLType_Simple::UREF),
            CLType::Option(inner) => {
                pb_type.mut_option_type().set_inner((*inner).into());
            }
            CLType::List(inner) => {
                pb_type.mut_list_type().set_inner((*inner).into());
            }
            CLType::FixedList(inner, len) => {
                let pb_fixed_list = pb_type.mut_fixed_list_type();
                pb_fixed_list.set_inner((*inner).into());
                pb_fixed_list.set_len(len);
            }
            CLType::Result { ok, err } => {
                let pb_result = pb_type.mut_result_type();
                pb_result.set_ok((*ok).into());
                pb_result.set_err((*err).into());
            }
            CLType::Map { key, value } => {
                let pb_map = pb_type.mut_map_type();
                pb_map.set_key((*key).into());
                pb_map.set_value((*value).into());
            }
            CLType::Tuple1(types) => {
                pb_type
                    .mut_tuple1_type()
                    .set_type0((*types[0].clone()).into());
            }
            CLType::Tuple2(types) => {
                let pb_tuple2 = pb_type.mut_tuple2_type();
                pb_tuple2.set_type0((*types[0].clone()).into());
                pb_tuple2.set_type1((*types[1].clone()).into());
            }
            CLType::Tuple3(types) => {
                let pb_tuple3 = pb_type.mut_tuple3_type();
                pb_tuple3.set_type0((*types[0].clone()).into());
                pb_tuple3.set_type1((*types[1].clone()).into());
                pb_tuple3.set_type2((*types[2].clone()).into());
            }
            CLType::Tuple4(types) => {
                let pb_tuple4 = pb_type.mut_tuple4_type();
                pb_tuple4.set_type0((*types[0].clone()).into());
                pb_tuple4.set_type1((*types[1].clone()).into());
                pb_tuple4.set_type2((*types[2].clone()).into());
                pb_tuple4.set_type3((*types[3].clone()).into());
            }
            CLType::Tuple5(types) => {
                let pb_tuple5 = pb_type.mut_tuple5_type();
                pb_tuple5.set_type0((*types[0].clone()).into());
                pb_tuple5.set_type1((*types[1].clone()).into());
                pb_tuple5.set_type2((*types[2].clone()).into());
                pb_tuple5.set_type3((*types[3].clone()).into());
                pb_tuple5.set_type4((*types[4].clone()).into());
            }
            CLType::Tuple6(types) => {
                let pb_tuple6 = pb_type.mut_tuple6_type();
                pb_tuple6.set_type0((*types[0].clone()).into());
                pb_tuple6.set_type1((*types[1].clone()).into());
                pb_tuple6.set_type2((*types[2].clone()).into());
                pb_tuple6.set_type3((*types[3].clone()).into());
                pb_tuple6.set_type4((*types[4].clone()).into());
                pb_tuple6.set_type5((*types[5].clone()).into());
            }
            CLType::Tuple7(types) => {
                let pb_tuple7 = pb_type.mut_tuple7_type();
                pb_tuple7.set_type0((*types[0].clone()).into());
                pb_tuple7.set_type1((*types[1].clone()).into());
                pb_tuple7.set_type2((*types[2].clone()).into());
                pb_tuple7.set_type3((*types[3].clone()).into());
                pb_tuple7.set_type4((*types[4].clone()).into());
                pb_tuple7.set_type5((*types[5].clone()).into());
                pb_tuple7.set_type6((*types[6].clone()).into());
            }
            CLType::Tuple8(types) => {
                let pb_tuple8 = pb_type.mut_tuple8_type();
                pb_tuple8.set_type0((*types[0].clone()).into());
                pb_tuple8.set_type1((*types[1].clone()).into());
                pb_tuple8.set_type2((*types[2].clone()).into());
                pb_tuple8.set_type3((*types[3].clone()).into());
                pb_tuple8.set_type4((*types[4].clone()).into());
                pb_tuple8.set_type5((*types[5].clone()).into());
                pb_tuple8.set_type6((*types[6].clone()).into());
                pb_tuple8.set_type7((*types[7].clone()).into());
            }
            CLType::Tuple9(types) => {
                let pb_tuple9 = pb_type.mut_tuple9_type();
                pb_tuple9.set_type0((*types[0].clone()).into());
                pb_tuple9.set_type1((*types[1].clone()).into());
                pb_tuple9.set_type2((*types[2].clone()).into());
                pb_tuple9.set_type3((*types[3].clone()).into());
                pb_tuple9.set_type4((*types[4].clone()).into());
                pb_tuple9.set_type5((*types[5].clone()).into());
                pb_tuple9.set_type6((*types[6].clone()).into());
                pb_tuple9.set_type7((*types[7].clone()).into());
                pb_tuple9.set_type8((*types[8].clone()).into());
            }
            CLType::Tuple10(types) => {
                let pb_tuple10 = pb_type.mut_tuple10_type();
                pb_tuple10.set_type0((*types[0].clone()).into());
                pb_tuple10.set_type1((*types[1].clone()).into());
                pb_tuple10.set_type2((*types[2].clone()).into());
                pb_tuple10.set_type3((*types[3].clone()).into());
                pb_tuple10.set_type4((*types[4].clone()).into());
                pb_tuple10.set_type5((*types[5].clone()).into());
                pb_tuple10.set_type6((*types[6].clone()).into());
                pb_tuple10.set_type7((*types[7].clone()).into());
                pb_tuple10.set_type8((*types[8].clone()).into());
                pb_tuple10.set_type9((*types[9].clone()).into());
            }
        };
        pb_type
    }
}

impl TryFrom<state::CLType> for CLType {
    type Error = ParsingError;

    fn try_from(pb_type: state::CLType) -> Result<Self, Self::Error> {
        let pb_type = pb_type
            .variants
            .ok_or_else(|| ParsingError("Unable to parse Protobuf CLType".to_string()))?;

        let cl_type = match pb_type {
            CLType_oneof_variants::simple_type(CLType_Simple::BOOL) => CLType::Bool,
            CLType_oneof_variants::simple_type(CLType_Simple::I32) => CLType::I32,
            CLType_oneof_variants::simple_type(CLType_Simple::I64) => CLType::I64,
            CLType_oneof_variants::simple_type(CLType_Simple::U8) => CLType::U8,
            CLType_oneof_variants::simple_type(CLType_Simple::U32) => CLType::U32,
            CLType_oneof_variants::simple_type(CLType_Simple::U64) => CLType::U64,
            CLType_oneof_variants::simple_type(CLType_Simple::U128) => CLType::U128,
            CLType_oneof_variants::simple_type(CLType_Simple::U256) => CLType::U256,
            CLType_oneof_variants::simple_type(CLType_Simple::U512) => CLType::U512,
            CLType_oneof_variants::simple_type(CLType_Simple::UNIT) => CLType::Unit,
            CLType_oneof_variants::simple_type(CLType_Simple::STRING) => CLType::String,
            CLType_oneof_variants::simple_type(CLType_Simple::KEY) => CLType::Key,
            CLType_oneof_variants::simple_type(CLType_Simple::UREF) => CLType::URef,
            CLType_oneof_variants::option_type(mut pb_option) => {
                let inner = pb_option.take_inner().try_into()?;
                CLType::Option(Box::new(inner))
            }
            CLType_oneof_variants::list_type(mut pb_list) => {
                let inner = pb_list.take_inner().try_into()?;
                CLType::List(Box::new(inner))
            }
            CLType_oneof_variants::fixed_list_type(mut pb_fixed_list) => {
                let inner = pb_fixed_list.take_inner().try_into()?;
                CLType::FixedList(Box::new(inner), pb_fixed_list.len)
            }
            CLType_oneof_variants::result_type(mut pb_result) => {
                let ok = pb_result.take_ok().try_into()?;
                let err = pb_result.take_err().try_into()?;
                CLType::Result {
                    ok: Box::new(ok),
                    err: Box::new(err),
                }
            }
            CLType_oneof_variants::map_type(mut pb_map) => {
                let key = pb_map.take_key().try_into()?;
                let value = pb_map.take_value().try_into()?;
                CLType::Map {
                    key: Box::new(key),
                    value: Box::new(value),
                }
            }
            CLType_oneof_variants::tuple1_type(mut pb_tuple1) => {
                let type0 = pb_tuple1.take_type0().try_into()?;
                CLType::Tuple1([Box::new(type0)])
            }
            CLType_oneof_variants::tuple2_type(mut pb_tuple2) => {
                let type0 = pb_tuple2.take_type0().try_into()?;
                let type1 = pb_tuple2.take_type1().try_into()?;
                CLType::Tuple2([Box::new(type0), Box::new(type1)])
            }
            CLType_oneof_variants::tuple3_type(mut pb_tuple3) => {
                let type0 = pb_tuple3.take_type0().try_into()?;
                let type1 = pb_tuple3.take_type1().try_into()?;
                let type2 = pb_tuple3.take_type2().try_into()?;
                CLType::Tuple3([Box::new(type0), Box::new(type1), Box::new(type2)])
            }
            CLType_oneof_variants::tuple4_type(mut pb_tuple4) => {
                let type0 = pb_tuple4.take_type0().try_into()?;
                let type1 = pb_tuple4.take_type1().try_into()?;
                let type2 = pb_tuple4.take_type2().try_into()?;
                let type3 = pb_tuple4.take_type3().try_into()?;
                CLType::Tuple4([
                    Box::new(type0),
                    Box::new(type1),
                    Box::new(type2),
                    Box::new(type3),
                ])
            }
            CLType_oneof_variants::tuple5_type(mut pb_tuple5) => {
                let type0 = pb_tuple5.take_type0().try_into()?;
                let type1 = pb_tuple5.take_type1().try_into()?;
                let type2 = pb_tuple5.take_type2().try_into()?;
                let type3 = pb_tuple5.take_type3().try_into()?;
                let type4 = pb_tuple5.take_type4().try_into()?;
                CLType::Tuple5([
                    Box::new(type0),
                    Box::new(type1),
                    Box::new(type2),
                    Box::new(type3),
                    Box::new(type4),
                ])
            }
            CLType_oneof_variants::tuple6_type(mut pb_tuple6) => {
                let type0 = pb_tuple6.take_type0().try_into()?;
                let type1 = pb_tuple6.take_type1().try_into()?;
                let type2 = pb_tuple6.take_type2().try_into()?;
                let type3 = pb_tuple6.take_type3().try_into()?;
                let type4 = pb_tuple6.take_type4().try_into()?;
                let type5 = pb_tuple6.take_type5().try_into()?;
                CLType::Tuple6([
                    Box::new(type0),
                    Box::new(type1),
                    Box::new(type2),
                    Box::new(type3),
                    Box::new(type4),
                    Box::new(type5),
                ])
            }
            CLType_oneof_variants::tuple7_type(mut pb_tuple7) => {
                let type0 = pb_tuple7.take_type0().try_into()?;
                let type1 = pb_tuple7.take_type1().try_into()?;
                let type2 = pb_tuple7.take_type2().try_into()?;
                let type3 = pb_tuple7.take_type3().try_into()?;
                let type4 = pb_tuple7.take_type4().try_into()?;
                let type5 = pb_tuple7.take_type5().try_into()?;
                let type6 = pb_tuple7.take_type6().try_into()?;
                CLType::Tuple7([
                    Box::new(type0),
                    Box::new(type1),
                    Box::new(type2),
                    Box::new(type3),
                    Box::new(type4),
                    Box::new(type5),
                    Box::new(type6),
                ])
            }
            CLType_oneof_variants::tuple8_type(mut pb_tuple8) => {
                let type0 = pb_tuple8.take_type0().try_into()?;
                let type1 = pb_tuple8.take_type1().try_into()?;
                let type2 = pb_tuple8.take_type2().try_into()?;
                let type3 = pb_tuple8.take_type3().try_into()?;
                let type4 = pb_tuple8.take_type4().try_into()?;
                let type5 = pb_tuple8.take_type5().try_into()?;
                let type6 = pb_tuple8.take_type6().try_into()?;
                let type7 = pb_tuple8.take_type7().try_into()?;
                CLType::Tuple8([
                    Box::new(type0),
                    Box::new(type1),
                    Box::new(type2),
                    Box::new(type3),
                    Box::new(type4),
                    Box::new(type5),
                    Box::new(type6),
                    Box::new(type7),
                ])
            }
            CLType_oneof_variants::tuple9_type(mut pb_tuple9) => {
                let type0 = pb_tuple9.take_type0().try_into()?;
                let type1 = pb_tuple9.take_type1().try_into()?;
                let type2 = pb_tuple9.take_type2().try_into()?;
                let type3 = pb_tuple9.take_type3().try_into()?;
                let type4 = pb_tuple9.take_type4().try_into()?;
                let type5 = pb_tuple9.take_type5().try_into()?;
                let type6 = pb_tuple9.take_type6().try_into()?;
                let type7 = pb_tuple9.take_type7().try_into()?;
                let type8 = pb_tuple9.take_type8().try_into()?;
                CLType::Tuple9([
                    Box::new(type0),
                    Box::new(type1),
                    Box::new(type2),
                    Box::new(type3),
                    Box::new(type4),
                    Box::new(type5),
                    Box::new(type6),
                    Box::new(type7),
                    Box::new(type8),
                ])
            }
            CLType_oneof_variants::tuple10_type(mut pb_tuple10) => {
                let type0 = pb_tuple10.take_type0().try_into()?;
                let type1 = pb_tuple10.take_type1().try_into()?;
                let type2 = pb_tuple10.take_type2().try_into()?;
                let type3 = pb_tuple10.take_type3().try_into()?;
                let type4 = pb_tuple10.take_type4().try_into()?;
                let type5 = pb_tuple10.take_type5().try_into()?;
                let type6 = pb_tuple10.take_type6().try_into()?;
                let type7 = pb_tuple10.take_type7().try_into()?;
                let type8 = pb_tuple10.take_type8().try_into()?;
                let type9 = pb_tuple10.take_type9().try_into()?;
                CLType::Tuple10([
                    Box::new(type0),
                    Box::new(type1),
                    Box::new(type2),
                    Box::new(type3),
                    Box::new(type4),
                    Box::new(type5),
                    Box::new(type6),
                    Box::new(type7),
                    Box::new(type8),
                    Box::new(type9),
                ])
            }
        };
        Ok(cl_type)
    }
}
