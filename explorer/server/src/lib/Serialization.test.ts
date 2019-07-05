import { expect } from 'chai';
import 'mocha';
import * as nacl from "tweetnacl-ts";
import { Args, PublicKeyArg, UInt64Arg } from './Serialization';

describe('PublicKeyArg', () => {
  it('should serialize as 32 bytes with size ++ content using little endiannes', () => {
    const key = nacl.sign_keyPair().publicKey;
    const result = PublicKeyArg(key);
    expect(result.length).to.equal(4 + 32);
    expect(result[0]).to.equal(32);
    expect(result[1]).to.equal(0);
    expect(result[4]).to.equal(key[0]);
    expect(result[35]).to.equal(key[31]);
  });
});

describe('UInt64Arg', () => {
  it('should serialize as 64 bits using little endiannes', () => {
    const input = BigInt(1234567890);
    const result = UInt64Arg(input);
    expect(result.length).to.equal(64 / 8);
    const output = Buffer.from(result).readBigInt64LE();
    expect(output).to.equal(input);
  });
});

describe('Args', () => {
  it('should serialize with size ++ concatenation of parts', () => {
    const a = nacl.sign_keyPair().publicKey;
    const b = BigInt(500000);
    const result = Args(PublicKeyArg(a), UInt64Arg(b));
    const buffer = Buffer.from(result);
    expect(result[0]).to.equal(2);
    expect(result[1]).to.equal(0);
    expect(result[4]).to.equal(32);
    expect(buffer.slice(4 + 4, 4 + 4 + 32).equals(a)).to.equal(true);
    expect(buffer.readBigInt64LE(4 + 4 + 32)).to.equal(b);
  });
});
