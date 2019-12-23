import React from 'react';
import { observer } from 'mobx-react';
import { computed, observable } from 'mobx';

interface Props {
  title?: string;
  toggleStore: ToggleStore;
  size: 'lg' | 'sm' | 'xs';
}

export class ToggleStore {
  @observable pressed: boolean = false;

  constructor(pressed: boolean) {
    this.pressed = pressed;
  }

  @computed
  get isPressed() {
    return this.pressed;
  }

  toggle() {
    this.pressed = !this.pressed;
  }
}

export const ToggleButton = observer((props: Props) => (
  <button
    type="button"
    className={`btn btn-${props.size} btn-toggle ${props.toggleStore
      .isPressed && 'active'}`}
    onClick={_ => props.toggleStore.toggle()}
    title={props.title}
  >
    <div className="handle" />
  </button>
));
