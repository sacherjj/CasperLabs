import { observer } from 'mobx-react';
import React from 'react';
import logo from '../img/CasperLabs_Logo_Favicon_RGB_50px.png';
import { Button } from 'react-bootstrap';
import { Redirect } from 'react-router-dom';
import { Form, TextField } from './Forms';
import { UnlockPageContainer } from '../container/UnlockPageContainer';
import AccountManager from '../container/AccountManager';
import { NoPreviousVaultError } from '../../lib/Errors';
import Pages from './Pages';

interface Props {
  authContainer: AccountManager;
  unlockPageContainer: UnlockPageContainer;
}

@observer
export default class UnlockPage extends React.Component<Props, {}> {
  async onSubmit() {
    let password = this.props.unlockPageContainer.password.$;
    try {
      await this.props.authContainer.unlock(password);
      this.props.unlockPageContainer.password.reset();
    } catch (e) {
      console.log(e);
      if (e instanceof NoPreviousVaultError) {
        this.props.unlockPageContainer.password.setError(e.message);
      } else {
        this.props.unlockPageContainer.password.setError('Incorrect Password');
      }
    }
  }

  render() {
    return this.props.authContainer.isUnLocked ? (
      <Redirect to={Pages.Home} />
    ) : (
      <div>
        <div className="mt-5 mb-4 text-center">
          <img src={logo} alt="logo" width={120} />
        </div>
        <h2 className="text-center mb-5">Welcome Back</h2>
        <div>
          <Form
            onSubmit={() => {
              this.onSubmit();
            }}
          >
            <TextField
              type="password"
              placeholder="Password"
              id="unlock-password"
              fieldState={this.props.unlockPageContainer.password}
            />
            <Button
              disabled={this.props.unlockPageContainer.submitDisabled}
              type="submit"
              block={true}
            >
              Unlock
            </Button>
          </Form>
        </div>
      </div>
    );
  }
}
