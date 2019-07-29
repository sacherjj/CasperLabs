import React from 'react';
import { RouteComponentProps, withRouter } from 'react-router-dom';
import { observer } from 'mobx-react';

// https://www.pluralsight.com/guides/react-router-typescript

// URL parameter
type Params = {
  blockHashBase16: string;
};

interface Props extends RouteComponentProps<Params> {}

@observer
class _Block extends React.Component<Props> {
  render() {
    return <div>{this.props.match.params.blockHashBase16}</div>;
  }
}

export const Block = withRouter(_Block);

export default Block;
