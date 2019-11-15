import TimeAgo from 'javascript-time-ago';
import en from 'javascript-time-ago/locale/en';
import React from 'react';

TimeAgo.addLocale(en);
const timeAgo = new TimeAgo();

const Timestamp = (props: { timestamp: number }) => {
  // Genesis has 0 timestamp which would print 50 years ago.
  const d = new Date(props.timestamp);
  return props.timestamp ? (
    <span title={d.toISOString()}>{timeAgo.format(d)}</span>
  ) : null;
};
export default Timestamp;
