import React from 'react';
import { observer } from 'mobx-react';
import DataTable from './DataTable';
import { BlockInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { encodeBase16 } from 'casperlabs-sdk';
import { Icon } from './Utils';

export const BondedValidatorsTable = observer(
  (props: { block: BlockInfo; lastFinalizedBlock: BlockInfo | undefined }) => {
    let finalizedBondedValidators = new Set();
    if (props.lastFinalizedBlock) {
      let finalizedBonds = props.lastFinalizedBlock
        .getSummary()!
        .getHeader()!
        .getState()!
        .getBondsList();
      finalizedBondedValidators = new Set(
        finalizedBonds.map(bond => bond.getValidatorPublicKey_asB64())
      );
    }
    let bondsList = props.block
      .getSummary()!
      .getHeader()!
      .getState()!
      .getBondsList();
    return (
      <DataTable
        title={`Bonded Validators List (${bondsList.length})`}
        headers={['Validator', 'Stake', 'Finalized']}
        rows={bondsList}
        renderRow={(bond, i) => {
          return (
            <tr key={i}>
              <td className="text-left">
                {encodeBase16(bond.getValidatorPublicKey_asU8())}
              </td>
              <td className="text-right">
                {Number(bond.getStake()!.getValue()).toLocaleString()}
              </td>
              <td className="text-center">
                {finalizedBondedValidators.has(
                  bond.getValidatorPublicKey_asB64()
                ) ? (
                  <Icon name="check-circle" color="green" />
                ) : (
                  <Icon name="clock" />
                )}
              </td>
            </tr>
          );
        }}
      />
    );
  }
);
