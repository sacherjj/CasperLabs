import React from 'react';
import { observer } from 'mobx-react';

import AuthContainer, { ImportAccountFormData, NewAccountFormData } from '../containers/AuthContainer';
import { Button, IconButton, ListInline, RefreshableComponent } from './Utils';
import Modal from './Modal';
import { FileSelect, Form, TextField } from './Forms';
import { base64to16, encodeBase16 } from 'casperlabs-sdk';
import { ObservableValue } from '../lib/ObservableValueMap';
import DataTable from './DataTable';

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
    const accountForm = this.props.auth.accountForm;

    let modalAccountForm;
    if (accountForm instanceof NewAccountFormData) {
      // Help IDE infer that the type of accountForm is NewAccountFormData
      let newAccountForm = accountForm;
      modalAccountForm = (
        <Modal
          id="new-account"
          title="Create Account Key"
          submitLabel="Save"
          onSubmit={() => this.props.auth.createAccount()}
          onClose={() => {
            this.props.auth.accountForm = null;
          }}
          error={newAccountForm.error}
        >
          <Form>
            <TextField
              id="id-account-name"
              label="Name"
              value={newAccountForm.name}
              placeholder="Human readable alias"
              onChange={x => {
                newAccountForm.name = x;
              }}
            />
            <TextField
              id="id-public-key-base64"
              label="Public Key (Base64)"
              value={newAccountForm.publicKeyBase64!}
              readonly={true}
            />
            <TextField
              id="id-public-key-base16"
              label="Public Key (Base16)"
              value={base64to16(newAccountForm.publicKeyBase64!)}
              readonly={true}
            />
            <TextField
              id="id-private-key-base64"
              label="Private Key (Base64)"
              value={newAccountForm.privateKeyBase64}
              readonly={true}
            />
            <TextField
              id="id-private-key-base16"
              label="Private Key (Base16)"
              value={base64to16(newAccountForm.privateKeyBase64!)}
              readonly={true}
            />
          </Form>
        </Modal>
      );
    } else if(accountForm instanceof ImportAccountFormData) {
      // Help IDE infer that the type of accountForm is ImportAccountFormData
      let importAccountForm = accountForm;
      modalAccountForm = (
          <Modal
            id="import-account"
            title="Import Account Public Key"
            submitLabel="Save"
            onSubmit={() => this.props.auth.importAccount()}
            onClose={() => {
              this.props.auth.accountForm = null;
            }}
            error={importAccountForm.error}
          >
            <Form>
              <FileSelect
                id="id-file-select"
                label={importAccountForm.fileName || 'Choose Public Key File'}
                handleFileSelect={e => {
                  importAccountForm.handleFileSelect(e);
                }}
              />
              <TextField
                id="id-account-name"
                label="Name"
                value={importAccountForm.name || ''}
                placeholder="Human readable alias"
                onChange={x => {
                  importAccountForm.name = x;
                }}
              />
              <TextField
                id="id-public-key-base64"
                label="Public Key (Base64)"
                value={importAccountForm.publicKeyBase64 || ''}
                readonly={true}
              />
            </Form>
          </Modal>
        );
    }
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
                  <Balance balance={balance}/>
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
        {modalAccountForm}
        <ListInline>
          <Button
            title="Create Account Key"
            onClick={() => this.props.auth.configureNewAccount()}
          />
          <Button
            onClick={() => this.props.auth.configureImportAccount()}
            title="Import Account Key"
          />
        </ListInline>
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
      value.balance === undefined ? 'n/a' : value.balance.toLocaleString();
    return (
      <div className="text-right" title={`As of block ${hash}`}>
        {balance}
      </div>
    );
  }
);
