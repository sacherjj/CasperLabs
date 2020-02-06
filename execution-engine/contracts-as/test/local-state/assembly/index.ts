//@ts-nocheck
import {Error} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {arrayToTyped} from "../../../../contract-as/assembly/utils";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {readLocal, writeLocal} from "../../../../contract-as/assembly/local";

const HELLO_PREFIX = " Hello, ";
const WORLD_SUFFIX = "world!";

enum CustomError {
  UnableToReadbackLocalValue = 0,
}

export function call(): void {
  const localBytes = new Array<u8>(32);
  localBytes.fill(66);
  const local = arrayToTyped(localBytes);

  let maybeValue =  readLocal(local);
  if(maybeValue === null){
    maybeValue = new Uint8Array(0);
  }

  let storedValue = fromBytesString(maybeValue);
  if (storedValue === null){
    storedValue = "";
  }

  writeLocal(local, CLValue.fromString(storedValue + HELLO_PREFIX));

  const maybeReadBack =  readLocal(local);
  if (maybeReadBack === null){
    Error.fromUserError(<u16>CustomError.UnableToReadbackLocalValue).revert()
    return;
  }

  let newValue = fromBytesString(maybeReadBack);
  if (newValue === null){
    newValue = "";
  }

  newValue = newValue + WORLD_SUFFIX;

  writeLocal(local, CLValue.fromString(newValue.trim()));
}
