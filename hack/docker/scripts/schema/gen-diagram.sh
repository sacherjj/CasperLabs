#!/usr/bin/env bash

set -e

# https://github.com/schemaspy/schemaspy

VERSION=6.1.1

if [ ! -f sqlite.db ]; then
  docker cp node-0:/root/.casperlabs/sqlite.db sqlite.db
  docker cp node-0:/root/.casperlabs/sqlite.db-shm sqlite.db-shm
  docker cp node-0:/root/.casperlabs/sqlite.db-wal sqlite.db-wal
fi

if [ ! -f schemaspy-${VERSION}-SNAPSHOT.jar ]; then
  curl -O https://github.com/schemaspy/schemaspy/releases/download/v${VERSION}/schemaspy-${VERSION}.jar
fi

if [ ! -f sqlite.properties ]; then
cat <<EOF > sqlite.properties
description=SQLite
connectionSpec=jdbc:sqlite:<db>
driver=org.sqlite.JDBC
EOF
fi

mkdir sqlite

java \
  -jar schemaspy-${VERSION}-SNAPSHOT.jar \
  -debug \
  -t sqlite \
  -db sqlite.db \
  -dp $PWD/../../../node/target/universal/stage/lib/org.xerial.sqlite-jdbc-3.28.0.jar \
  -u 'sqlite' \
  -cat sqlite \
  -schemas 'sqlite' \
  -o sqlite


xdg-open sqlite/index.html
