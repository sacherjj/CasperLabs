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
