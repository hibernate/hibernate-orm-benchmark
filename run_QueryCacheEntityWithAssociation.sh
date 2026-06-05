#!/bin/bash

usage() {
  echo "Usage: $0 [orm_version]"
  echo
  echo "  orm_version    The ORM version to test (e.g. 7.4, 6.6, 5.6)."
  echo "                 Defaults to latest (7.4) if not specified."
}

if [[ "$1" == "-h" || "$1" == "--help" ]]; then
  usage
  exit 0
fi

ORM_VERSION=${1:-default}
./gradlew jmhJar ${1:+-Porm=$1}
java -jar basic/target/libs/hibernate-orm-benchmark-basic-1.0-SNAPSHOT-jmh.jar QueryCacheEntityWithAssociation -f 2 -prof gc -prof "async:rawCommand=alloc,wall;event=cpu;output=jfr;dir=/tmp;libPath=${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.so" -pcount=100

java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --alloc --total /tmp/org.hibernate.benchmark.queryl2hit.QueryCacheEntityWithAssociation.single-Throughput-cacheLayout-SHALLOW/jfr-cpu.jfr QueryCacheEntityWithAssociation-SHALLOW-alloc-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state default /tmp/org.hibernate.benchmark.queryl2hit.QueryCacheEntityWithAssociation.single-Throughput-cacheLayout-SHALLOW/jfr-cpu.jfr QueryCacheEntityWithAssociation-SHALLOW-cpu-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state runnable,sleeping /tmp/org.hibernate.benchmark.queryl2hit.QueryCacheEntityWithAssociation.single-Throughput-cacheLayout-SHALLOW/jfr-cpu.jfr QueryCacheEntityWithAssociation-SHALLOW-wall-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --alloc --total /tmp/org.hibernate.benchmark.queryl2hit.QueryCacheEntityWithAssociation.single-Throughput-cacheLayout-FULL/jfr-cpu.jfr QueryCacheEntityWithAssociation-FULL-alloc-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state default /tmp/org.hibernate.benchmark.queryl2hit.QueryCacheEntityWithAssociation.single-Throughput-cacheLayout-FULL/jfr-cpu.jfr QueryCacheEntityWithAssociation-FULL-cpu-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state runnable,sleeping /tmp/org.hibernate.benchmark.queryl2hit.QueryCacheEntityWithAssociation.single-Throughput-cacheLayout-FULL/jfr-cpu.jfr QueryCacheEntityWithAssociation-FULL-wall-${ORM_VERSION}.html