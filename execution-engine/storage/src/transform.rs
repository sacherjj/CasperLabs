use common::key::Key;
use common::value::Value;
use std::collections::BTreeMap;
use std::fmt;
use std::ops::Add;

#[derive(PartialEq, Eq, Debug, Clone)]
pub struct TypeMismatch {
    pub expected: String,
    pub found: String,
}
impl TypeMismatch {
    pub fn new(expected: String, found: String) -> TypeMismatch {
        TypeMismatch { expected, found }
    }
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum Transform {
    Identity,
    Write(Value),
    AddInt32(i32),
    AddKeys(BTreeMap<String, Key>),
    Failure(TypeMismatch),
}

use self::Transform::*;

impl Transform {
    pub fn apply(self, v: Value) -> Result<Value, TypeMismatch> {
        match self {
            Identity => Ok(v),
            Write(w) => Ok(w),
            AddInt32(i) => match v {
                Value::Int32(j) => Ok(Value::Int32(i + j)),
                other => {
                    let expected = String::from("Int32");
                    Err(TypeMismatch {
                        expected,
                        found: other.type_string(),
                    })
                }
            },
            AddKeys(mut keys) => match v {
                Value::Contract(mut c) => {
                    c.insert_urefs(&mut keys);
                    Ok(c.into())
                }
                Value::Account(mut a) => {
                    a.insert_urefs(&mut keys);
                    Ok(Value::Account(a))
                }
                other => {
                    let expected = String::from("Contract or Account");
                    Err(TypeMismatch {
                        expected,
                        found: other.type_string(),
                    })
                }
            },
            Failure(error) => Err(error),
        }
    }
}

impl Add for Transform {
    type Output = Transform;

    fn add(self, other: Transform) -> Transform {
        match (self, other) {
            (a, Identity) => a,
            (Identity, b) => b,
            (a @ Failure(_), _) => a,
            (_, b @ Failure(_)) => b,
            (_, b @ Write(_)) => b,
            (Write(v), b) => {
                // second transform changes value being written
                match b.apply(v) {
                    Err(error) => Failure(error),
                    Ok(new_value) => Write(new_value),
                }
            }
            (AddInt32(i), b) => match b {
                AddInt32(j) => AddInt32(i + j),
                other => Failure(TypeMismatch {
                    expected: "AddInt32".to_owned(),
                    found: format!("{:?}", other),
                }),
            },
            (AddKeys(mut ks1), b) => match b {
                AddKeys(mut ks2) => {
                    ks1.append(&mut ks2);
                    AddKeys(ks1)
                }
                other => Failure(TypeMismatch {
                    expected: "AddKeys".to_owned(),
                    found: format!("{:?}", other),
                }),
            },
        }
    }
}

impl fmt::Display for Transform {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}
