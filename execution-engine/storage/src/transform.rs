use common::key::Key;
use common::value::Value;
use std::collections::BTreeMap;
use std::fmt;
use std::ops::Add;

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum Transform {
    Identity,
    Write(Value),
    AddInt32(i32),
    AddKeys(BTreeMap<String, Key>),
}

use self::Transform::*;

impl Transform {
    pub fn apply(&mut self, v: Value) -> Result<Value, super::Error> {
        match self {
            Identity => Ok(v),
            Write(w) => Ok(w.clone()),
            AddInt32(i) => match v {
                Value::Int32(j) => Ok(Value::Int32(*i + j)),
                other => {
                    let expected = String::from("Int32");
                    Err(super::Error::TypeMismatch {
                        expected,
                        found: other.type_string(),
                    })
                }
            },
            AddKeys(keys) => match v {
                Value::Contract {
                    mut known_urefs,
                    bytes,
                } => {
                    known_urefs.append(keys);
                    Ok(Value::Contract { bytes, known_urefs })
                }
                Value::Acct(mut a) => {
                    a.insert_urefs(keys);
                    Ok(Value::Acct(a))
                }
                other => {
                    let expected = String::from("Contract or Account");
                    Err(super::Error::TypeMismatch {
                        expected,
                        found: other.type_string(),
                    })
                }
            },
        }
    }
}

impl Add for Transform {
    type Output = Transform;

    fn add(self, other: Transform) -> Transform {
        match (self, other) {
            (a, Identity) => a,
            (Identity, b) => b,
            (_, b @ Write(_)) => b,
            (Write(v), mut b) => {
                //second transform changes value being written
                let new_value = b.apply(v).unwrap();
                Write(new_value)
            }
            (AddInt32(i), b) => match b {
                AddInt32(j) => AddInt32(i + j),
                _ => panic!("Attempted to add an integer to a non-integer!"),
            },
            (AddKeys(mut ks1), b) => match b {
                AddKeys(mut ks2) => {
                    ks1.append(&mut ks2);
                    AddKeys(ks1)
                }
                _ => panic!("Attempted to add keys to non-keys!"),
            },
        }
    }
}

impl fmt::Display for Transform {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}
