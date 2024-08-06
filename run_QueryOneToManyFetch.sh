#!/bin/bash

function usage() {
  echo "Usage:"
  echo
  echo "  $0 <orm_version>"
  echo
  echo "    <orm>                The ORM version to test (e.g. 6.6 or perf)"
}

ORM_VERSION=$1

if [ -z "$ORM_VERSION" ]; then
	echo "ERROR: ORM version not supplied"
	usage
	exit 1
fi

./gradlew jmhJar -Porm=${ORM_VERSION}
java -jar basic/target/libs/hibernate-orm-benchmark-basic-1.0-SNAPSHOT-jmh.jar QueryOneToManyFetch -f 2 -prof gc -prof "async:rawCommand=alloc,wall;event=cpu;output=jfr;dir=/tmp;libPath=${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.so" -pcount=100

java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --alloc --total /tmp/org.hibernate.benchmark.onetomanyfetch.QueryOneToManyFetch.single-Throughput-orderLines-1/jfr-cpu.jfr QueryOneToManyFetch-1-alloc-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state default /tmp/org.hibernate.benchmark.onetomanyfetch.QueryOneToManyFetch.single-Throughput-orderLines-1/jfr-cpu.jfr QueryOneToManyFetch-1-cpu-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state runnable,sleeping /tmp/org.hibernate.benchmark.onetomanyfetch.QueryOneToManyFetch.single-Throughput-orderLines-1/jfr-cpu.jfr QueryOneToManyFetch-1-wall-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --alloc --total /tmp/org.hibernate.benchmark.onetomanyfetch.QueryOneToManyFetch.single-Throughput-orderLines-50/jfr-cpu.jfr QueryOneToManyFetch-50-alloc-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state default /tmp/org.hibernate.benchmark.onetomanyfetch.QueryOneToManyFetch.single-Throughput-orderLines-50/jfr-cpu.jfr QueryOneToManyFetch-50-cpu-${ORM_VERSION}.html
java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --state runnable,sleeping /tmp/org.hibernate.benchmark.onetomanyfetch.QueryOneToManyFetch.single-Throughput-orderLines-50/jfr-cpu.jfr QueryOneToManyFetch-50-wall-${ORM_VERSION}.html