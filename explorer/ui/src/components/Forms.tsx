import React from 'react';
import { observer } from 'mobx-react';

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

interface FormProps {
  // Expecting to see `<Field />` nested
  // or `<dif class="form-row"><Field /><Field/>`
  children?: any;
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

export const Form = (props: FormProps) => (
  <form>
    {props.children.map((group: any, idx: number) => (
      <div className="form-group" key={idx}>
        {group}
      </div>
    ))}
  </form>
);
