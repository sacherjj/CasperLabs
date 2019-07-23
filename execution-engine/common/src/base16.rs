use crate::alloc::prelude::String;

/// Encodes a slice of bytes in base16 form in lower case
pub fn encode_lower(input: &[u8]) -> String {
    input.iter().map(|b| format!("{:02x}", b)).collect()
}

#[test]
fn test_encode_lower() {
    assert_eq!(encode_lower(&[1, 2, 3, 254, 255]), "010203feff");
    assert_eq!(encode_lower(&[]), "");
}
