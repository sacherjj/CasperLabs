import React from 'react';

import CasperContainer from '../containers/CasperContainer';
import AuthContainer from '../containers/AuthContainer';
import { useAuth0 } from "../react-auth0-wrapper";
import App from './App';

export interface MainProps {
  casper: CasperContainer;
}

// Hooks for Auth0 can only be used in a functional component.
// Since most components currently are classes let's inject it here.
const Main = (props: MainProps) => {
  const auth = new AuthContainer(useAuth0());
  return <App casper={props.casper} auth={auth} />;
}

export default Main;
