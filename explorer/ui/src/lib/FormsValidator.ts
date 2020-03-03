import validator from 'validator';
import { decodeBase16 } from 'casperlabs-sdk';

export const valueRequired = (val: any) => !val && 'Value required';

export const numberBigThan: (n: number) => (val: number) => string|false = (n: number) => {
  return (val: number) => {
    return !(val > n) && `Value should bigger than ${n}`;
  }
};

export const isInt = (n: number) => !validator.isInt(n.toString()) && 'Value should be an Integer';

export const isBase16 = (val: string) => {
  try {
    decodeBase16(val);
  } catch (e) {
    return "Could not decode as Base16 hash.";
  }
  return false; // indicate no error
};

export const isBlockHashBase16 = (hashBase16: string) => {
  if (hashBase16.length < 64)
    return 'Ths Hash has to be 64 characters long.';

  return isBase16(hashBase16);
};
