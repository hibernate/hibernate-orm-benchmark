#!/bin/bash

usage() {
  echo "Usage: $0 [orm_version]"
  echo
  echo "  orm_version    The full ORM version to test (e.g. 7.4.5.Final)."
  echo "                 Defaults to the version in basic/build.gradle if not specified."
}

if [[ "$1" == "-h" || "$1" == "--help" ]]; then
  usage
  exit 0
fi

./gradlew jmhJar ${1:+-PormVersion=$1}
java -jar basic/target/libs/hibernate-orm-benchmark-basic-1.0-SNAPSHOT-jmh.jar QueryWithEnabledFilter -f 2 -prof gc
