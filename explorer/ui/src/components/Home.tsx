import React from 'react';
import { Link } from 'react-router-dom';
import { observer } from 'mobx-react';

import Pages from './Pages';
import CasperContainer from '../containers/CasperContainer';
import AuthContainer from '../containers/AuthContainer';

interface Props {
  casper: CasperContainer;
  auth: AuthContainer;
}

const Home = observer((props: Props) => {
  return (
    <div>
      <div className="jumbotron shadow">
        <div>
          <h1>CasperLabs Explorer</h1>
          <p>
            This is a self service portal for dApp developers to interact with
            the blockchain. On devnet you can use this portal to create accounts
            for yourself, fund them with some free tokens to play with, and
            explore the blockchain. If you're having an issue then don't
            hesitate to let us know on{' '}
            <a href="https://t.me/casperlabs">Telegram</a> or{' '}
            <a href="https://github.com/CasperLabs/CasperLabs/issues">Github</a>
          </p>
          <ul className="list-inline" id="go-to-buttons">
            <li className="list-inline-item">
              <a
                className="btn btn-success btn-lg"
                href="https://techspec.casperlabs.io/"
                role="button"
              >
                Read our Tech Spec &raquo;
              </a>
            </li>
          </ul>
        </div>
      </div>

      <div className="row">
        <AccountsCard accounts={props.auth.accounts} />
        {props.auth.accounts && props.auth.accounts.length > 0 && (
          <FaucetCard />
        )}
        <ExploreCard />
      </div>

      <div className="card">
        <div className="card-header bg-danger text-white">
          Looking for help?
        </div>
        <div className="card-body">
          <p>
            To write contracts have a look at the{' '}
            <a href="https://github.com/CasperLabs/contract-examples/tree/master">
              contract examples
            </a>
            , the{' '}
            <a href="https://github.com/CasperLabs/CasperLabs/blob/dev/README.md">
              main docs
            </a>
            and the{' '}
            <a href="https://github.com/CasperLabs/CasperLabs/tree/dev/hack/docker">
              local docker network setup
            </a>
            .
          </p>
        </div>
      </div>

      <br />
    </div>
  );
});

export default Home;

interface CardProps {
  background: string;
  icon: string;
  to?: string;
  children: any;
}

// Card displays coloured summaries for main areas
const Card = (props: CardProps) => (
  <div className="col-xl-3 col-sm-6 mb-3">
    <div className={`card text-white bg-${props.background} o-hidden h-100`}>
      <div className="card-body">
        <div className="card-body-icon">
          <i className={`fa fa-fw fa-${props.icon}`}></i>
        </div>
        {props.children}
      </div>
      {props.to && (
        <Link
          className="card-footer text-white clearfix small z-1"
          to={props.to}
        >
          <span className="float-left">View Details</span>
          <span className="float-right">
            <i className="fa fa-angle-right"></i>
          </span>
        </Link>
      )}
    </div>
  </div>
);

const CardMessage = (props: { message: string }) => {
  return <div className="mr-5">{props.message}</div>;
};

const AccountsCard = (props: { accounts: UserAccount[] | null }) => {
  const background =
    props.accounts && props.accounts.length > 0 ? 'success' : 'primary';
  return (
    <Card background={background} icon="address-book" to={Pages.Accounts}>
      {props.accounts == null || props.accounts.length === 0 ? (
        <CardMessage message="Create an account" />
      ) : (
        <CardMessage message={`You have ${props.accounts.length} account(s)`} />
      )}
    </Card>
  );
};

const FaucetCard = (_: {}) => {
  // TODO: Display available funds.
  return (
    <Card background="primary" icon="coins" to={Pages.Faucet}>
      <CardMessage message="Request tokens" />
    </Card>
  );
};

const ExploreCard = (_: {}) => {
  // TODO: Display latest block timestamp.
  return (
    <Card background="success" icon="project-diagram" to={Pages.Explorer}>
      <CardMessage message="Explore the blockchain" />
    </Card>
  );
};

// TODO: DeployCard with the cached deploys to send another one?
// TODO: BlocksCard with the last finalized block, or the tips?
