use casperlabs_contract_ffi::key::AccessRights;
use proptest::prelude::*;

proptest! {
    #[test]
    fn eqv_access_is_implicit(access_right in gens::gens::access_rights_arb()) {
        assert_eq!(AccessRights::Eqv <= access_right, true);
    }
}
