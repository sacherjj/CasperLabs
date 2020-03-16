import { useHistory } from 'react-router-dom';
import { Button } from 'react-bootstrap';
import React from 'react';

export const LinkButton = (props: {
  title: string,
  path: string
}) => {
  let history = useHistory();

  function handleClick() {
    history.push(props.path);
  }

  return (
    <Button onClick={handleClick}>
      {props.title}
    </Button>
  );
};

export const ListInline = (props: { children: any }) => {
  const children = [].concat(props.children);
  return (
    <ul className="list-inline mb-0">
      {children.map((child: any, idx: number) => (
        <li key={idx} className="list-inline-item">
          {child}
        </li>
      ))}
    </ul>
  );
};
