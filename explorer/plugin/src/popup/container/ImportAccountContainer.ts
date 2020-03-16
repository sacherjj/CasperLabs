import { FieldState } from 'formstate';
import { fieldSubmittable, valueRequired } from '../../lib/FormValidator';
import { computed } from 'mobx';


export class ImportAccountContainer {
  privateKey: FieldState<string> = new FieldState<string>('').validators(valueRequired);
  publicKey: FieldState<string> = new FieldState<string>('').validators(valueRequired);
  name: FieldState<string> = new FieldState<string>('').validators(valueRequired);

  @computed
  get submitDisabled(): boolean {
    return !(fieldSubmittable(this.privateKey) && fieldSubmittable(this.name));
  }
}
