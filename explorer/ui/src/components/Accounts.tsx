import React from 'react';
import { observer } from 'mobx-react';

import AuthContainer from '../containers/AuthContainer';
import { RefreshableComponent, Button, IconButton, ListInline } from './Utils';
import DataTable from './DataTable';
import Modal from './Modal';
import { Form, TextField } from './Forms';
import { base64to16, encodeBase16 } from '../lib/Conversions';
import { ObservableValue } from '../lib/ObservableValueMap';

interface Props {
  auth: AuthContainer;
}

@observer
export default class Accounts extends RefreshableComponent<Props, {}> {
  async refresh(force?: boolean) {
    await this.props.auth.refreshAccounts();
    await this.props.auth.refreshBalances(force);
  }

  render() {
    const newAccount = this.props.auth.newAccount;
    return (
      <div>
        <DataTable
          title="Accounts"
          refresh={() => this.refresh(true)}
          rows={this.props.auth.accounts}
          headers={[
            'Name',
            'Public Key (Base64)',
            'Public Key (Base16)',
            'Balance',
            ''
          ]}
          renderRow={(account: UserAccount) => {
            const balance = this.props.auth.balances.get(
              account.publicKeyBase64
            );
            return (
              <tr key={account.name}>
                <td>{account.name}</td>
                <td>{account.publicKeyBase64}</td>
                <td>{base64to16(account.publicKeyBase64)}</td>
                <td>
                  <Balance balance={balance} />
                </td>
                <td className="text-center">
                  <IconButton
                    onClick={() => this.props.auth.deleteAccount(account.name)}
                    title="Delete"
                    icon="trash-alt"
                  />
                </td>
              </tr>
            );
          }}
          footerMessage={
            <span>
              You can create a new account here, which is basically an Ed25519
              public key. Don't worry, the private key will never leave the
              browser, we'll save it straight to disk on your machine.
            </span>
          }
        />

        <ListInline>
          <Button
            title="Create Account"
            onClick={() => this.props.auth.configureNewAccount()}
          />
        </ListInline>

        {newAccount && (
          <Modal
            id="new-account"
            title="Create Account"
            submitLabel="Save"
            onSubmit={() => this.props.auth.createAccount()}
            onClose={() => {
              this.props.auth.newAccount = null;
            }}
            error={newAccount.error}
          >
            <Form>
              <TextField
                id="id-account-name"
                label="Name"
                value={newAccount.name}
                placeholder="Human readable alias"
                onChange={x => {
                  newAccount.name = x;
                }}
              />
              <TextField
                id="id-public-key-base64"
                label="Public Key (Base64)"
                value={newAccount.publicKeyBase64!}
                readonly={true}
              />
              <TextField
                id="id-public-key-base16"
                label="Public Key (Base16)"
                value={base64to16(newAccount.publicKeyBase64!)}
                readonly={true}
              />
              <TextField
                id="id-private-key-base64"
                label="Private Key (Base64)"
                value={newAccount.privateKeyBase64!}
                readonly={true}
              />
              <TextField
                id="id-private-key-base16"
                label="Private Key (Base16)"
                value={base64to16(newAccount.privateKeyBase64!)}
                readonly={true}
              />
            </Form>
          </Modal>
        )}
      </div>
    );
  }
}

// Need an observer component to subscribe just to this account balance.
const Balance = observer(
  (props: { balance: ObservableValue<AccountBalance> }) => {
    const value = props.balance.value;
    if (value == null) return null;

    const hash = encodeBase16(value.blockHash);
    const balance =
      value.balance == null ? 'n/a' : value.balance.toLocaleString();
    return (
      <div className="text-right" title={`As of block ${hash}`}>
        {balance}
      </div>
    );
  }
);
