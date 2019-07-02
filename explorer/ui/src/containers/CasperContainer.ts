//import { observable } from 'mobx';

import ErrorContainer from './ErrorContainer';

// CasperContainer talks to the API on behalf of React
// components and exposes the state in MobX observables.
export class CasperContainer {
  constructor(private errors: ErrorContainer) {}
}

export default CasperContainer;
