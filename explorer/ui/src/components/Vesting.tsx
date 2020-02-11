import React, { ReactNode } from 'react';
import { observer } from 'mobx-react';
import { Form, SelectField, TextField } from './Forms';
import AuthContainer from '../containers/AuthContainer';
import { Button, Card, Icon, ListInline, Loading, RefreshableComponent, ShortHashSpan } from './Utils';
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
      <div>
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
          <div>
            <Card title="Vesting Schedule">
              <div className="col-8 container">
                <VestingChart vestingDetail={vesting.vestingDetails}/>
              </div>
            </Card>
            <VestingDetails hash={auth.selectedVestingHash!.hashBase16}
                            vestingDetail={vesting.vestingDetails}
                            refresh={
                              () => vesting.init(auth.selectedVestingHash!.hashBase16)
                            }/>
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
            value={(auth.selectedVestingHash && auth.selectedVestingHash.name) || null}
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
          <Button title="Remove" type="danger" onClick={() => {
            auth.deleteVestingHash(auth.selectedVestingHash!.hashBase16).then(() => {
              auth.selectedVestingHash = null;
            });
          }} disabled={auth.selectedVestingHash === null}/>
        </ListInline>
      </Card>
    );
  }
);

const TableRow = (props: { title: string; children: ReactNode }) => {
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
          {props.hash}
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
         <span className="mr-3">
          {duration(props.vestingDetail.admin_release_duration * 1000)}
         </span>
          {props.vestingDetail.is_releasable && (
            <Icon name="check-circle" color="green" title="Available to release"/>
          )}
        </TableRow>
        <TableRow title="Paused State">
          {props.vestingDetail.is_paused ? 'Paused' : 'Not Paused'}
        </TableRow>
        {
          props.vestingDetail.is_paused && (
            <TableRow title="Last Time Paused">
              {moment(props.vestingDetail.last_pause_timestamp * 1000).fromNow()}
            </TableRow>
          )
        }
        <TableRow title="On Pause Duration">
          {duration(props.vestingDetail.on_pause_duration)}
        </TableRow>
        <TableRow title="Admin Account">
          {props.vestingDetail.admin_account}
        </TableRow>
        <TableRow title="Recipient Account">
          {props.vestingDetail.recipient_account}
        </TableRow>
        <TableRow title="Available Amount">
          {props.vestingDetail.available_amount.toLocaleString() + ' CLX'}
        </TableRow>
        </tbody>
      </table>
    </Card>
  )
);

export default Vesting;
