#[cfg(test)]
mod tests {
    use common::key::AccessRights;

    proptest! {
        #[test]
        fn eqv_access_is_implicit(access_right in gens::gens::access_rights_arb()) {
            let gen_access_right: AccessRights = access_right;
            assert_eq!(AccessRights::Eqv <= gen_access_right, true);
        }
    }

    #[test]
    fn reads_partial_ordering() {
        let read = AccessRights::Read;
        assert_eq!(read == AccessRights::Read, true);
        assert_eq!(read < AccessRights::ReadAdd, true);
        assert_eq!(read < AccessRights::ReadWrite, true);
        assert_eq!(read != AccessRights::Add, true);
        assert_eq!(read != AccessRights::Write, true);
    }

    #[test]
    fn adds_partial_ordering() {
        let add = AccessRights::Add;
        assert_eq!(add == AccessRights::Add, true);
        assert_eq!(add < AccessRights::ReadAdd, true);
        assert_eq!(add < AccessRights::ReadWrite, true);
        assert_eq!(add != AccessRights::Read, true);
        assert_eq!(add != AccessRights::Write, true);
    }

    #[test]
    fn writes_partial_ordering() {
        let write = AccessRights::Write;
        assert_eq!(write == AccessRights::Write, true);
        assert_eq!(write < AccessRights::ReadWrite, true);
        assert_eq!(write != AccessRights::Read, true);
        assert_eq!(write != AccessRights::Add, true);
        assert_eq!(write != AccessRights::ReadAdd, true);
    }
}