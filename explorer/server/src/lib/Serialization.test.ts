import { expect } from 'chai';
import 'mocha';
import { PublicKey } from './Serialization';

describe('PublicKey', () => {

  it('should serialize with little endiannes', () => {
    const key = Buffer.alloc(32, 1);
    const result = PublicKey(key);
    expect(result.length).to.equal(4 + 32);
    expect(result[0]).to.equal(32);
    expect(result[1]).to.equal(0);
  });

});
