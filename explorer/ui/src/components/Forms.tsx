import React from 'react';
import { observer } from 'mobx-react';
import { ListInline } from './Utils';

interface FieldProps {
  id: string;
  label: string;
  value: string | null;
  placeholder?: string;
  onChange?: (value: string) => void; // Leave it out with readonly
  readonly?: boolean;
  valid?: boolean | null;
}

interface Option {
  label: string;
  value: string;
}

interface TextProps extends FieldProps {
  unit?: string;
  numeric?: boolean;
}

interface SelectProps extends FieldProps {
  options: Option[];
}

interface RadioProps extends SelectProps {}

interface FormProps {
  // Expecting to see `<Field />` nested
  // or `<dif class="form-row"><Field /><Field/>`
  children?: any;
  onSubmit?: () => void;
}

function controlClass(props: FieldProps) {
  let validity =
    props.valid == null || props.valid === undefined
      ? ''
      : props.valid
      ? 'is-valid'
      : 'is-invalid';
  return ['form-control', validity].join(' ');
}

export const TextField = observer((props: TextProps) => {
  let input = (
    <input
      className={controlClass(props)}
      id={props.id}
      type="text"
      placeholder={props.placeholder}
      value={props.value || ''}
      readOnly={props.readonly || false}
      onChange={e => props.onChange!(e.target.value)}
    />
  );
  let cu = (props.unit && 'has-unit') || '';
  let cn = (props.numeric && 'numeric') || '';
  return (
    <div className={[cu, cn].filter(x => x !== '').join(' ')}>
      <label htmlFor={props.id}>{props.label}</label>
      {(props.unit && (
        <div className="input-group">
          {input}
          <div className="input-group-addon">{props.unit}</div>
        </div>
      )) ||
        input}
    </div>
  );
});

export const SelectField = observer((props: SelectProps) => (
  <div>
    <label htmlFor={props.id}>{props.label}</label>
    <select
      className={controlClass(props)}
      id={props.id}
      value={props.value || ''}
      onChange={e => props.onChange!(e.target.value)}
    >
      {props.placeholder && (
        <option disabled value="">
          --- {props.placeholder} ---
        </option>
      )}
      {props.options.map(opt => (
        <option key={opt.value} value={opt.value}>
          {opt.label}
        </option>
      ))}
    </select>
  </div>
));

export const RadioField = observer((props: RadioProps) => (
  <div>
    {
      <div>
        <label>{props.label}</label>

        <ListInline>
          {props.options.map((opt, i) => {
            const id = `${props.id}/${i}`;
            return (
              <div key={i} className="ml-2">
                <input
                  className="mr-1"
                  id={id}
                  name={props.id}
                  type="radio"
                  value={opt.value}
                  readOnly={props.readonly || false}
                  onChange={e => props.onChange!(e.target.value)}
                  checked={opt.value === props.value}
                />
                <label htmlFor={id}>{opt.label}</label>
              </div>
            );
          })}
        </ListInline>
      </div>
    }
  </div>
));

export const Form = (props: FormProps) => (
  <form
    onSubmit={e => {
      e.preventDefault();
      props.onSubmit && props.onSubmit();
      return false;
    }}
  >
    {props.children.map((group: any, idx: number) => (
      <div className="form-group" key={idx}>
        {group}
      </div>
    ))}
  </form>
);

export const ErrorMessage = (props: { error: string | null }) =>
  props.error ? (
    <div className="alert alert-danger" role="alert">
      {props.error}
    </div>
  ) : null;
