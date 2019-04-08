use casperlabs_contract_ffi::key::AccessRights;
use core::hash::{Hash, Hasher};
use gens::gens::*;
use proptest::prelude::*;
use siphasher::sip::SipHasher;

proptest! {
    #[test]
    fn eqv_access_is_implicit(access_right in access_rights_arb()) {
        assert_eq!(AccessRights::Eqv <= access_right, true);
    }

    // According to the Rust documentation:
    // https://doc.rust-lang.org/std/hash/trait.Hash.html#hash-and-eq
    // following property must hold:
    // k1 == k2 -> hash(k1) == hash(k2)
    #[test]
    fn test_key_eqv_hash_property(key_a in key_arb(), key_b in key_arb()) {
        if key_a == key_b {
            let mut hasher = SipHasher::new();
            let key_a_hash = {
                key_a.hash(&mut hasher);
                hasher.finish()
            };
            let key_b_hash = {
                key_b.hash(&mut hasher);
                hasher.finish()
            };
            assert!(key_a_hash == key_b_hash);
        }
    }
}
