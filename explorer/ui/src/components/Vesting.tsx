import React from 'react';
import { observer } from 'mobx-react';
import { Form, SelectField, TextField } from './Forms';
import AuthContainer from '../containers/AuthContainer';
import { FaucetRequest } from '../containers/FaucetContainer';
import { Button, Card, Icon, ListInline, Loading, RefreshableComponent, shortHash } from './Utils';
import { DeployInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { decodeBase64, encodeBase16 } from 'casperlabs-sdk';
import VestingChart from './VestringChart';
import moment from 'moment';
import { VestingContainer, VestingDetail } from '../containers/VestingContainer';
import Modal from './Modal';

interface Props {
  auth: AuthContainer;
  vesting: VestingContainer;
}

@observer
class Vesting extends RefreshableComponent<Props, {}> {
  refresh() {
    this.props.auth.refreshAccounts();
  }

  render() {
    const { auth, vesting } = this.props;
    return (
      <div className="container">
        <VestingHashesManageForm auth={auth} requestVestingDetails={x =>
          this.props.vesting.init(x, true).catch(() => {
            let msg = `The hash is not valid anymore, do you want to remove it?`;
            auth.deleteVestingHash(x, msg).then(auth.selectedVestingHash = null);
          })
        }/>
        {auth.selectedVestingHash && !vesting.vestingDetails && (
          <div className="col-12">
            <Loading/>
          </div>
        )}
        {auth.selectedVestingHash && vesting.vestingDetails && (
          <div className="row">
            <div className="col-6">
              <VestingDetails hash={auth.selectedVestingHash!.hashBase16}
                              vestingDetail={vesting.vestingDetails}
                              refresh={
                                () => vesting.init(auth.selectedVestingHash!.hashBase16)
                              }
              />
            </div>
            <div className="col-6">
              <VestingChart vestingDetail={vesting.vestingDetails}/>
            </div>
          </div>
        )}
      </div>
    );
  }
}

/**
 * The ui component to manage hashes of Vesting Contract,
 * including selecting, adding and removing hashes.
 */
const VestingHashesManageForm = observer(
  (props: {
    auth: AuthContainer;
    requestVestingDetails: (hashBase16: string) => void;
  }) => {
    const { auth, requestVestingDetails } = props;

    // The modal for importing new hash, showed once users click the `Add New` button.
    const modalImporting = (auth.importVestingForm !== null &&
      <Modal
        id="addNewVestingHash"
        title="Add new hash of vesting contract"
        submitLabel="Save"
        onSubmit={() => auth.addVestingItem()}
        onClose={() => {
          auth.importVestingForm = null;
        }}
        error={auth.importVestingForm!.error}
      >
        <Form>
          <TextField
            id="id-import-vesting-hash-base64"
            label="Hash of vesting contract (Base16)"
            value={auth.importVestingForm!.hashBase16 || ''}
            onChange={x => {
              auth.importVestingForm!.hashBase16 = x;
            }}
          />
          <TextField
            id="id-import-vesting-hash-name"
            label="Name"
            value={auth.importVestingForm!.name || ''}
            placeholder="Human readable alias"
            onChange={x => {
              auth.importVestingForm!.name = x;
            }}
          />
        </Form>
      </Modal>
    );

    return (
      <Card
        title="Vesting Contracts"
      >
        <Form>
          <SelectField
            id="id-vesting-name"
            label="Name"
            placeholder="Select hash of the vesting contract"
            value={auth.selectedVestingHash && auth.selectedVestingHash.name || null}
            options={(auth.vestingHashes || []).map(x => ({
              label: x.name,
              value: x.name
            }))}
            onChange={x => {
              let oldSelected = auth.selectedVestingHash;
              auth.selectVestingHashByName(x);

              // Check whether user select another vesting contract
              if (oldSelected !== auth.selectedVestingHash && auth.selectedVestingHash) {
                requestVestingDetails(auth.selectedVestingHash.hashBase16);
              }
            }}
          />
          <TextField
            id="id-vesting-hash-base16"
            label="Hash of vesting contract (Base16)"
            value={auth.selectedVestingHash?.hashBase16 || null}
            readonly={true}
          />
        </Form>
        {modalImporting}
        <ListInline>
          <Button
            title="Add New"
            onClick={() => auth.configureImportVestingHash()}
          />
          <Button title="Remove" style="danger" onClick={() => {
            auth.deleteVestingHash(auth.selectedVestingHash!.hashBase16).then(() => {
              auth.selectedVestingHash = null;
            });
          }} disabled={auth.selectedVestingHash === null}/>
        </ListInline>
      </Card>
    );
  }
);

const TableRow = (props: { title: string; children: string }) => {
  return (
    <tr>
      <th role="row">{props.title}</th>
      <td>
        {props.children}
      </td>
    </tr>
  );
};

/**
 * Get a string represent how long the duration is.
 * @param duration : milliseconds
 */
function duration(duration: number) {
  const d = moment.duration(duration);
  if (d.days() > 1) {
    let days = d.asDays();
    return `${days.toLocaleString()} Day${days > 1 ? 's' : ''}`;
  } else {
    let hours = d.asHours();
    return `${hours.toLocaleString()} Hour${hours > 1 ? 's' : ''}`;
  }
}

/**
 * The table to show the information of the selected vesting contract.
 */
const VestingDetails = observer(
  (props: {
    vestingDetail: VestingDetail,
    hash: string
    refresh: () => void
  }) => (
    <Card
      title="Vesting Details"
      refresh={() => props.refresh()}
    >
      <table className="table table-bordered">
        <tbody>
        <TableRow title="Hash of the Vesting Contract">
          {shortHash(props.hash)}
        </TableRow>
        <TableRow title="Current Time">
          {moment().format()}
        </TableRow>
        <TableRow title="Cliff Timestamp">
          {moment(props.vestingDetail.cliff_timestamp * 1000).fromNow()}
        </TableRow>
        <TableRow title="Cliff Amount">
          {props.vestingDetail.cliff_amount.toLocaleString() + ' CLX'}
        </TableRow>
        <TableRow title="Drip Duration">
          {duration(props.vestingDetail.drip_duration * 1000)}
        </TableRow>
        <TableRow title="Drip Amount">
          {props.vestingDetail.drip_amount.toLocaleString() + ' CLX'}
        </TableRow>
        <TableRow title="Total Amount">
          {props.vestingDetail.total_amount.toLocaleString() + ' CLX'}
        </TableRow>
        <TableRow title="Released Amount">
          {props.vestingDetail.released_amount.toLocaleString() + ' CLX'}
        </TableRow>
        <TableRow title="Admin Release Duration">
          {duration(props.vestingDetail.admin_release_duration * 1000)}
        </TableRow>
        <TableRow title="Paused State">
          Paused
        </TableRow>
        <TableRow title="Admin Account">
          xxx
        </TableRow>
        <TableRow title="Recipient account">
          aaa
        </TableRow>
        <TableRow title="Available Amount">
          {props.vestingDetail.available_amount.toLocaleString() + ' CLX'}
        </TableRow>
        </tbody>
      </table>
    </Card>
  )
);

const StatusCell = observer((props: { request: FaucetRequest }) => {
  const info = props.request.deployInfo;
  const iconAndMessage: () => [any, string | undefined] = () => {
    if (info) {
      const attempts = info.processingResultsList;
      const success = attempts.find(x => !x.isError);
      const failure = attempts.find(x => x.isError);
      const blockHash = (result: DeployInfo.ProcessingResult.AsObject) => {
        const h = result.blockInfo!.summary!.blockHash;
        return encodeBase16(typeof h === 'string' ? decodeBase64(h) : h);
      };
      if (success)
        return [
          <Icon name="check-circle" color="green"/>,
          `Successfully included in block ${blockHash(success)}`
        ];
      if (failure) {
        const errm = failure.errorMessage;
        const hint =
          errm === 'Exit code: 1'
            ? '. It looks like you already funded this account!'
            : errm === 'Exit code: 2'
            ? '. It looks like the faucet ran out of funds!'
            : '';
        return [
          <Icon name="times-circle" color="red"/>,
          `Failed in block ${blockHash(failure)}: ${errm + hint}`
        ];
      }
    }
    return [<Icon name="clock"/>, 'Pending...'];
  };
  const [icon, message] = iconAndMessage();
  return (
    <td className="text-center" title={message}>
      {icon}
    </td>
  );
});

export default Vesting;
