import * as React from 'react';

export const Loading = () => (
  <div className="text-center">
    <i className="fa fa-fw fa-spin fa-spinner" />
    Loading...
  </div>
);

export const RefreshButton = (props: { refresh: () => void }) => (
  <a onClick={_ => props.refresh()} title="Refresh">
    <i className="fa fa-fw fa-refresh" />
  </a>
);

// RefreshableComponent calls it's `refresh()` when it
// has mounted where it should get data from the server.
// It should either then use `setState` or wait for MobX
// to notify it of any changes. We can also call this
// method from the callback of a refresh button, or
// add a method here to start a timer which should be
// stopped in `componentWillUnmount`.
export abstract class RefreshableComponent<P, S> extends React.Component<P, S> {
  abstract refresh(): void;

  protected refreshIntervalMillis: number = 0;
  protected timerId: number = 0;

  // See all lifecycle methods at https://reactjs.org/docs/react-component.html
  componentDidMount() {
    this.refresh();
    if (this.refreshIntervalMillis > 0) {
      this.timerId = window.setInterval(
        () => this.refresh(),
        this.refreshIntervalMillis
      );
    }
  }

  componentWillUnmount() {
    if (this.timerId !== 0) {
      window.clearInterval(this.timerId);
    }
  }
}

export const UnderConstruction = (message: string) => {
  return (
    <div className="card shadow">
      <div className="card-header bg-warning">
        <h4 className="card-title font-weight-bold text-white">
          Under construction
        </h4>
      </div>
      <div className="card-body">{message}</div>
    </div>
  );
};
