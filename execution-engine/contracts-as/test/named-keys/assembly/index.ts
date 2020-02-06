import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {Key} from "../../../../contract-as/assembly/key";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {U512} from "../../../../contract-as/assembly/bignum";
import {CLValue} from "../../../../contract-as/assembly/clvalue";


export function call(): void {
  let initi_uref_num = 2;
  let namedKeys = CL.listNamedKeys();
  if (namedKeys.length != initi_uref_num) {
    Error.fromUserError(4464).revert();
    return;
  }

  let helloWorldKey = Key.create(CLValue.fromString("Hello, world!"));
  if (helloWorldKey === null) {
    Error.fromUserError(4464 + 1).revert();
    return;
  }

  CL.putKey("hello-world", helloWorldKey);

  if (CL.listNamedKeys().length != initi_uref_num + 1) {
    Error.fromUserError(4464 + 2).revert();
    return;
  }
  
  if (!CL.hasKey("hello-world")) {
    Error.fromUserError(4464 + 3).revert();
    return;
  }

  let newBigValueKey = Key.create(CLValue.fromU512(U512.MAX_VALUE));
  if (newBigValueKey === null) {
    Error.fromUserError(4464 + 4).revert();
    return;
  }
  CL.putKey("big-value", newBigValueKey);

  if (CL.listNamedKeys().length != initi_uref_num + 2) {
    Error.fromUserError(4464 + 5).revert();
    return;
  }

  // Read data hidden behind `URef1` uref
  namedKeys = CL.listNamedKeys();

  let helloWorld: String = "";
  for (let i = 0; i < namedKeys.length; i++) {
    if (namedKeys[i].first == "hello-world") {
      let bytes = namedKeys[i].second.read();
      if (bytes === null) {
        Error.fromUserError(4464 + 1000 + <u16>i).revert();
        return;
      }

      let bytesString = fromBytesString(bytes);
      if (bytesString === null) {
        Error.fromUserError(4464 + 2000 + <u16>i).revert();
        return;
      }
      helloWorld = bytesString;
    }
  }

  if (helloWorld != "Hello, world!") {
    Error.fromUserError(4464 + 6).revert();
    return;
  }

  // Read data through dedicated FFI function
  let uref1 = CL.getKey("hello-world");
  let uref1Bytes = uref1.read();
  if (uref1Bytes === null) {
    Error.fromUserError(4464 + 7).revert();
    return;
  }
  let uref1Str = fromBytesString(uref1Bytes);
  if (uref1Str === null) {
    Error.fromUserError(4464 + 8).revert();
    return;
  }
  if (uref1Str != "Hello, world!") {
    Error.fromUserError(4464 + 9).revert();
    return;
  }

  // Remove uref
  CL.removeKey("hello-world");
  if (CL.hasKey("hello-world")) {
    Error.fromUserError(4464 + 10).revert();
    return;
  }

  // Confirm URef2 is still there
  if (!CL.hasKey("big-value")) {
    Error.fromUserError(4464 + 11).revert();
    return;
  }
  // Get the big value back
  let bigValueKey = CL.getKey("big-value");
  let bigValueBytes = bigValueKey.read();
  if (bigValueBytes === null) {
    Error.fromUserError(4464 + 12).revert();
    return;
  }
  let bigValue = U512.fromBytes(bigValueBytes);
  if (bigValue === null) {
    Error.fromUserError(4464 + 13).revert();
    return;
  }

  if (bigValue != U512.MAX_VALUE) {
    Error.fromUserError(4464 + 14).revert();
    return;
  }

  // Increase by 1
  bigValueKey.add(CLValue.fromU512(U512.fromU64(1)));
  let newBigValueBytes = bigValueKey.read();
  if (newBigValueBytes === null) {
    Error.fromUserError(4464 + 15).revert();
    return;
  }
  let newBigValue = U512.fromBytes(newBigValueBytes);
  if (newBigValue === null) {
    Error.fromUserError(4464 + 16).revert();
    return;
  }
  if (newBigValue != U512.MIN_VALUE) {
    Error.fromUserError(4464 + 17).revert();
    return;
  }

  // I can overwrite some data under the pointer
  bigValueKey.write(CLValue.fromU512(U512.fromU64(123456789)));
  
  newBigValueBytes = bigValueKey.read();
  if (newBigValueBytes === null) {
    Error.fromUserError(4464 + 18).revert();
    return;
  }
  newBigValue = U512.fromBytes(newBigValueBytes);
  if (newBigValue === null) {
    Error.fromUserError(4464 + 19).revert();
    return;
  }
  if (newBigValue != U512.fromU64(123456789)) {
    Error.fromUserError(4464 + 20).revert();
    return;
  }

  // Try to remove non existing uref which shouldn't fail
  CL.removeKey("hello-world");
  CL.removeKey("big-value");

  // Cleaned up state

  if (CL.hasKey("hello-world")) {
    Error.fromUserError(4464 + 21).revert();
    return;
  }
  if (CL.hasKey("big-value")) {
    Error.fromUserError(4464 + 22).revert();
    return;
  }

  if (CL.listNamedKeys().length != initi_uref_num) {
    Error.fromUserError(4464 + 23).revert();
    return;
  }
}
