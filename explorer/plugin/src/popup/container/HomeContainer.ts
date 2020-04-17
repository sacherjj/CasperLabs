import { FieldState } from 'formstate';
import { valueRequired } from '../../lib/FormValidator';
import { computed } from 'mobx';

export class HomeContainer {
  password: FieldState<string> = new FieldState<string>('').validators(
    valueRequired
  );

  @computed
  get submitDisabled(): boolean {
    return (
      !this.password.hasBeenValidated ||
      (this.password.hasBeenValidated && this.password.hasError)
    );
  }
}
