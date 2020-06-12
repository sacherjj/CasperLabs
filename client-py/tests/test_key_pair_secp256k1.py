from casperlabs_client.key_holders import SECP256K1Key


def test_generate_ethereum():
    key_holder = SECP256K1Key.generate()

    assert len(key_holder.private_key) == 24
    private_parts = key_holder.private_key_pem.split(b"-----")
    assert private_parts[1] == b"BEGIN EC PRIVATE KEY"
    assert private_parts[3] == b"END EC PRIVATE KEY"

    public_parts = key_holder.public_key_pem.split(b"-----")
    assert public_parts[1] == b"BEGIN PUBLIC KEY"
    assert public_parts[3] == b"END PUBLIC KEY"


def test_ethereum_round_trip_private_key():
    private_key = b"_\xbc\xcc\xb6\xfc\xfbbFE\x11\xc2\xa4\xe6\x0c%>\x1b\x1c\x939\xc8wJ!"
    first_key = SECP256K1Key(private_key=private_key)
    private_pem = first_key.private_key_pem  # generated from private_key given
    other_key = SECP256K1Key(private_key_pem=private_pem)
    result_private_key = other_key.private_key  # generated from private_key_pem given
    assert (
        result_private_key == private_key
    ), "private key after round trip doesn't match"


def test_ethereum_round_trip_private_key_pem():
    private_key_pem = (
        b"-----BEGIN EC PRIVATE KEY-----\n"
        b"MF8CAQEEGDu+eFCwupMo8NYfM9lfKOaDUheNG3hU2aAKBggqhkjOPQMBAaE0AzIA\n"
        b"BIkNrSoHz6ecup+dNrZI15YrN5j8wBl0T/sm0lnxXHBWJvE5annOc65YZqQuyypW\n"
        b"aQ==\n"
        b"-----END EC PRIVATE KEY-----\n"
    )
    first_key = SECP256K1Key(private_key_pem=private_key_pem)
    private_key = first_key.private_key
    other_key = SECP256K1Key(private_key=private_key)
    result_private_key_pem = other_key.private_key_pem
    assert (
        result_private_key_pem == private_key_pem
    ), "private key pem after roundtrip doesn't match"


def test_ethereum_account_hash():
    public_key = b'h\xc0\xf9\x9aj[\xb7\x97q\xe0\xe6\xd1\x97\x96\x9e[\xffV\xb7\x17\xb3\xac\x03/\xad\x93\xc5\xd61qp~\x9b&\xb1\x1b\x19#\x8d\xed\xa6\x85\xc5`W"\xbcO'
    key_holder = SECP256K1Key(public_key=public_key)
    expected_account_hash = b"\xa24^\x87\x17\x8d\xee\x86\xcf\xcfk\x9b\x8d\xe8\x13\x11H2\\N'\xca\xce\x1c\xfb\xde\xb6\xdb/\xd2-I"
    account_hash = key_holder.account_hash
    assert account_hash == expected_account_hash, "account_hash does not equal expected"
