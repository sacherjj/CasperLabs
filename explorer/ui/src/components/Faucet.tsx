import React from 'react';
import { observer } from 'mobx-react';
import { Form, SelectField, TextField } from './Forms';
import AuthContainer from '../containers/AuthContainer';
import { CasperContainer, FaucetRequest } from '../containers/CasperContainer';
import { RefreshableComponent, Button, UnderConstruction } from './Utils';
import DataTable from './DataTable';
import { base64to16, encodeBase16 } from '../lib/Conversions';

interface Props {
  auth: AuthContainer;
  casper: CasperContainer;
}

@observer
class Faucet extends RefreshableComponent<Props, {}> {
  refresh() {
    this.props.auth.refreshAccounts();
  }

  render() {
    const { auth, casper } = this.props;

    const faucetForm = (
      <div className="card mb-3">
        <div className="card-header">
          <span>Faucet</span>
        </div>
        <div className="card-body">
          <Form>
            <SelectField
              id="id-account-name"
              label="Account"
              placeholder="Select account"
              value={
                (auth.selectedAccount && auth.selectedAccount.name) || null
              }
              options={(auth.accounts || []).map(x => ({
                label: x.name,
                value: x.name
              }))}
              onChange={x => auth.selectAccountByName(x)}
            />
            <TextField
              id="id-public-key-base16"
              label="Public Key (Base16)"
              value={
                auth.selectedAccount &&
                base64to16(auth.selectedAccount.publicKeyBase64)
              }
              readonly={true}
            />
          </Form>
          <Button
            title="Request tokens"
            disabled={auth.selectedAccount == null}
            onClick={() =>
              this.props.casper.requestTokens(auth.selectedAccount!)
            }
          />
        </div>
        <div className="card-footer small text-muted">
          Pick one of your accounts that you haven't yet received free tokens
          for and submit a request. We'll send a deploy to the blockchain that
          will transfer you some tokens to play with. The UI will display the
          status of the deploy until it's finalized, so you know when you can
          start using the funds.
        </div>
      </div>
    );

    const statusTable = casper.faucetRequests.length > 0 && (
      <DataTable
        title="Request Status"
        refresh={() => this.refresh()}
        rows={casper.faucetRequests}
        headers={['Timestamp', 'Account', 'Deploy Hash']}
        renderRow={(request: FaucetRequest, idx: number) => {
          return (
            <tr key={idx}>
              <td>{request.timestamp.toLocaleString()}</td>
              <td>{request.account.name}</td>
              <td>{encodeBase16(request.deployHash)}</td>
            </tr>
          );
        }}
        footerMessage={
          <span>
            Wait until the deploy is included in a block and that block gets
            finalized.
          </span>
        }
      />
    );

    const cliHint = casper.faucetRequests.length > 0 && (
      <UnderConstruction>
        <p>
          Automatic monitoring for status hasn't been implemented yet. You have
          to do it manually using the{' '}
          <a
            href="https://github.com/CasperLabs/CasperLabs/blob/dev/README.md#cli-client-tool-1"
            target="_blank"
          >
            casperlabs-client
          </a>
          :
        </p>
        <pre>
          {
            'casperlabs-client --host <node-address> --port 40401 show-deploys <deploy-hash>'
          }
        </pre>
      </UnderConstruction>
    );

    return (
      <div>
        {faucetForm}
        {statusTable}
        {cliHint}
      </div>
    );
  }
}

export default Faucet;
