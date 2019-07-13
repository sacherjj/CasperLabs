import React from 'react';
import { UnderConstruction } from './Utils';

const Explorer = () => (
  <UnderConstruction>
    <p>
      Interactive blockchain browser with D3. Meanwhile you can use the CLI
      client to{' '}
      <a
        href="https://github.com/CasperLabs/CasperLabs/tree/dev/hack/docker#visualizing-the-dag"
        target="_blank"
        rel="noopener noreferrer"
      >
        visualize
      </a>{' '}
      the DAG.
    </p>
  </UnderConstruction>
);

export default Explorer;
