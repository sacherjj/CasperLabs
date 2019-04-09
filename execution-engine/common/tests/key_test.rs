use casperlabs_contract_ffi::key::AccessRights;
use core::cmp::Ordering;
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

    // According to Rust documentation, following property must hold::
    // forall a, b: a.cmp(b) == Ordering::Equal iff a == b and Some(a.cmp(b)) == a.partial_cmp(b)
    #[test]
    fn test_key_partialeq_partialord_ord_property(key_a in key_arb(), key_b in key_arb()) {
        if key_a == key_b && Some(key_a.cmp(&key_b)) == key_a.partial_cmp(&key_b) {
            assert!(key_a.cmp(&key_b) == Ordering::Equal);
        }
    }
}
