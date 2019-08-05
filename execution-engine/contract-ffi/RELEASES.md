Version 0.8.0 (2019-06-14)
==========================
* [[#598]](https://github.com/CasperLabs/CasperLabs/pull/598) Factors out `URef` from `Key` variant to its own type.
* [[#599]](https://github.com/CasperLabs/CasperLabs/pull/599) Adds `purseId` object to an `Account`.

Version 0.7.0 (2019-06-09)
==========================
* [[#563]](https://github.com/CasperLabs/CasperLabs/pull/563) Change encoding of URef's access rights to `Option<..>`.
* [[#604]](https://github.com/CasperLabs/CasperLabs/pull/604) Fixed serialization bug in URef's access rights encoding.

Version 0.6.0 (2019-05-28)
==========================
* `read_local` and `write_local` functions are defined for interacting with the new "context-local partitions of global state".

Version 0.5.0 (2019-04-10)
==========================
* API method `call_contract` has been simplified. Instead of requiring arguments passed in the binary form it now accepts tuples up to 8 elements. It is required that for every type in the tuple there exists an instance of `ToBytes` trait.

Version 0.4.0 (2019-04-03)
==========================

* `new_uref` FFI now requires an initial value
  [commit](https://github.com/CasperLabs/CasperLabs/pull/327/commits/58393ada6a41cf9a068125845c83dfc8af961b03).
  This prevents creating "dangling" `URef`s.

Version 0.2.0 (2019-03-14)
==========================

* Renamed `ext` module to `contract_api`
  [commit](https://github.com/CasperLabs/CasperLabs/commit/e158bddc2a9282ad6edcd6561d509514fd0693cd#diff-d140dd56f8d99d4f77fcdb8bc85e1238).
* Replaced untyped pointers in the FFI with typed versions
  [commit](https://github.com/CasperLabs/CasperLabs/blob/dev/execution-engine/common/src/contract_api/pointers.rs).
* [`new_uref` method now expects initial value (of some type `T`) and
  produces typed, unforgeable reference
  (`UPointer<T>`)](https://github.com/CasperLabs/CasperLabs/blob/92c02d23c9f03ab6b816ac37c8581e5929e5da7f/execution-engine/common/src/contract_api/mod.rs#L66).
* Changed the API for interacting with the Global State. Following FFI
  functions operate on unforgeable references only:
  - [`read`. Accepts typed unforgeable reference (`UPointer<T>`) and
    returns `T` (or `panic!`s if value under the key can't be parsed
    as
    `T`](https://github.com/CasperLabs/CasperLabs/blob/92c02d23c9f03ab6b816ac37c8581e5929e5da7f/execution-engine/common/src/contract_api/mod.rs#L15).
  - [`write`. Accepts typed unforgeable reference (`UPointer<T>`) and
    value of type
    `T`](https://github.com/CasperLabs/CasperLabs/blob/92c02d23c9f03ab6b816ac37c8581e5929e5da7f/execution-engine/common/src/contract_api/mod.rs#L34).
  - [`add`. Accepts typed unforgeable reference (`UPointer<T>`) and
    value of type `T`. May `panic` if `T` is not a `Monoid` (cannot
    add two values of type `T`) or the value living under the
    unforgeable reference is not of type
    `T`](https://github.com/CasperLabs/CasperLabs/blob/92c02d23c9f03ab6b816ac37c8581e5929e5da7f/execution-engine/common/src/contract_api/mod.rs#L49).
  - [`store_function` now returns
    `ContractPointer`](https://github.com/CasperLabs/CasperLabs/blob/92c02d23c9f03ab6b816ac37c8581e5929e5da7f/execution-engine/common/src/contract_api/mod.rs#L104).
  - [`call_contract` accepts
    `ContractPointer`](https://github.com/CasperLabs/CasperLabs/blob/92c02d23c9f03ab6b816ac37c8581e5929e5da7f/execution-engine/common/src/contract_api/mod.rs#L182).
* Added implementations for `From`/`TryFrom` for easy conversion
  between `value::Value` and rust base types
  [commit](https://github.com/CasperLabs/CasperLabs/commit/fb7bb153037f43f0bab8ceb5ff6755bae89c453c#diff-056afd53406aa3eedc8f003ae6afd3eaR177).
