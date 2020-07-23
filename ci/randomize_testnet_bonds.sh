#!/bin/bash
set -eo pipefail

TEMP_FILE="/tmp/accounts.csv.tmp"
ACCOUNTS_CSV_PATH="../testnet/accounts.csv"
LINE_COUNT=`cat ${ACCOUNTS_CSV_PATH} | wc -l`
OFFSET='5'
SUM_OF_BONDS='0'

# Cleanup old temp path if exist
if [ -f "$TEMP_FILE" ]; then
  rm -f $TEMP_FILE
  echo "Removing Old Temp File ..."
else
  echo "No Temp File Found, Continuing ..."
fi

echo "Generating Temp File ..."
# Keep first entry the faucet
awk 'FNR==1 {print $0}' $ACCOUNTS_CSV_PATH >> $TEMP_FILE

# Iterater set to offset
i=$OFFSET
while [ "$i" -le "$LINE_COUNT" ]; do
  # Random integer from 1-100
  rand_int=$(( $RANDOM % 100 + 1 ))
  SUM_OF_BONDS=$(($SUM_OF_BONDS + $rand_int))
  # Output new random bond values to temp file
  awk -v iter=$i -v rand_int=$rand_int -F',' \
      'FNR==iter {$4=rand_int; print $0}' OFS=, $ACCOUNTS_CSV_PATH >> $TEMP_FILE
  i=$(($i + 1))
done

echo "Sum Of External Validators: $SUM_OF_BONDS"

# I think this math checks out :), Blame Joe if it doesn't he checked it!
INTERNAL_BOND_SUM=$((2 * $SUM_OF_BONDS / 3))
INTERNAL_NODES_BOND=$(($INTERNAL_BOND_SUM / 3))
echo "Sum Of Internal Validators: $INTERNAL_BOND_SUM"
echo "Internal Nodes Will Have A Bond OF: $INTERNAL_NODES_BOND"

# Add Internal nodes back
i=2
while [ "$i" -lt "$OFFSET" ]; do
  # grab the old lines and update them
  temp_line=`awk -v iter=$i -v rand_int=$INTERNAL_NODES_BOND -F',' \
      'FNR==iter {$4=rand_int; print $0}' OFS=, $ACCOUNTS_CSV_PATH`
  # insert them after the faucet
  sed -i "1 a $temp_line" $TEMP_FILE
  i=$(($i + 1))
done

MD5_ACCOUNTS=`md5sum $ACCOUNTS_CSV_PATH | awk '{ print $1 }'`
MD5_TEMP=`md5sum $TEMP_FILE | awk '{ print $1 }'`

# Check file actually changed
if [ "$MD5_ACCOUNTS" = "$MD5_TEMP" ]; then
  echo "Failure: $TEMP_FILE Matches $ACCOUNTS_CSV_PATH!"
  echo "$ACCOUNTS_CSV_PATH = $MD5_ACCOUNTS"
  echo "$TEMP_FILE = $MD5_TEMP"
  exit 1
else
  echo "MD5's Differ, Continuing ..."
  mv $TEMP_FILE $ACCOUNTS_CSV_PATH
fi

echo "Done!"
