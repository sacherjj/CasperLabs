import React from 'react';
import { observer } from 'mobx-react';

import AuthContainer from '../containers/AuthContainer';
import { RefreshableComponent, Button, IconButton, ListInline } from './Utils';
import DataTable from './DataTable';
import Modal from './Modal';
import { Form, TextField } from './Forms';
import { base64to16 } from '../lib/Conversions';

interface Props {
  auth: AuthContainer;
}

@observer
export default class Accounts extends RefreshableComponent<Props, {}> {
  refresh() {
    this.props.auth.refreshAccounts();
  }

  render() {
    const newAccount = this.props.auth.newAccount;
    return (
      <div>
        <DataTable
          title="Accounts"
          refresh={() => this.refresh()}
          rows={this.props.auth.accounts}
          headers={['Name', 'Public Key (Base64)', 'Public Key (Base16)', '']}
          renderRow={(account: UserAccount) => {
            return (
              <tr key={account.name}>
                <td>{account.name}</td>
                <td>{account.publicKeyBase64}</td>
                <td>{base64to16(account.publicKeyBase64)}</td>
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
                label="Public Key (Base64)"
                value={newAccount.privateKeyBase64!}
                readonly={true}
              />
              <TextField
                id="id-private-key-base16"
                label="Public Key (Base16)"
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
