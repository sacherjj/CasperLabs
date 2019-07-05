
import { expect } from 'chai';
import 'mocha';
import { decodeBase64 } from 'tweetnacl-ts';
import { byteHash } from './Contracts';

describe('byteHash', () => {
  it('should compute the same value as Scala', () => {
    const inputBase64 = 'CiD3h4YVBZm1ChNTR29eLxLNE8IU5RIJZ0HEjn7GNjmvVhABGOO9g5q8LSpA4X7mRaRWddGbdOmIM9Fm9p0QxFKvVBscD5dmu1YdPK29ufR/ZmI0oseKM6l5RVKIUO3hh5en5prtkrrCzl3sdw=='
    const input = decodeBase64(inputBase64);
    const hash = byteHash(input);
    const hashHex = Buffer.from(hash).toString('hex');
    const expectedHex = 'e0c7d8fbcbfd7eb5231b779cb4d7dcbcc3d60846e5a198a2c66bb1d3aafbd9a7';
    expect(hashHex).to.equal(expectedHex);
    expect(hash.length).to.equal(32);
  });
});
