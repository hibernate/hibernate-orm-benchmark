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
java -jar basic/target/libs/hibernate-orm-benchmark-basic-1.0-SNAPSHOT-jmh.jar QueryEntityQueryReferringEntityAll -f 2 -prof gc -prof "async:rawCommand=alloc,wall;event=cpu;output=jfr;dir=/tmp;libPath=${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.so" -pcount=100

java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --alloc --total /tmp/org.hibernate.benchmark.queryl1hit.QueryEntityQueryReferringEntityAll.noQueryAccess-Throughput/jfr-cpu.jfr QueryEntityQueryReferringEntityAll-noQueryAccess-alloc-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state default /tmp/org.hibernate.benchmark.queryl1hit.QueryEntityQueryReferringEntityAll.noQueryAccess-Throughput/jfr-cpu.jfr QueryEntityQueryReferringEntityAll-noQueryAccess-cpu-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state runnable,sleeping /tmp/org.hibernate.benchmark.queryl1hit.QueryEntityQueryReferringEntityAll.noQueryAccess-Throughput/jfr-cpu.jfr QueryEntityQueryReferringEntityAll-noQueryAccess-wall-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --alloc --total /tmp/org.hibernate.benchmark.queryl1hit.QueryEntityQueryReferringEntityAll.includeQueryAccess-Throughput/jfr-cpu.jfr QueryEntityQueryReferringEntityAll-includeQueryAccess-alloc-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state default /tmp/org.hibernate.benchmark.queryl1hit.QueryEntityQueryReferringEntityAll.includeQueryAccess-Throughput/jfr-cpu.jfr QueryEntityQueryReferringEntityAll-includeQueryAccess-cpu-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state runnable,sleeping /tmp/org.hibernate.benchmark.queryl1hit.QueryEntityQueryReferringEntityAll.includeQueryAccess-Throughput/jfr-cpu.jfr QueryEntityQueryReferringEntityAll-includeQueryAccess-wall-${ORM_VERSION}.html