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

java -jar basic/target/libs/hibernate-orm-benchmark-basic-1.0-SNAPSHOT-jmh.jar PersistentCollections -pcount=100 -pinit_collections=false \
 -f 2 -prof gc -prof "async:rawCommand=features=vtable;event=cpu;output=jfr;dir=/tmp;libPath=${ASYNC_PROFILER_HOME}/lib/libasyncProfiler.so"

# first search for all the jfr files in /tmp which contains PersistentCollections as name
# then run the jfr2flame tool to generate the flamegraphs

# Find all jfr files in /tmp containing PersistentCollections in the name
jfr_files=$(find /tmp/org.hibernate.benchmark.collections.PersistentCollections*/jfr-cpu.jfr)
# list the jfr files with some comment in between
echo "JFR files found:"
echo $jfr_files
# Run the jfr2flame tool to generate the flamegraphs
echo "Flamegraphs produced:"
for jfr_file in $jfr_files; do
  java -cp ${ASYNC_PROFILER_HOME}/lib/converter.jar jfr2flame --lines $jfr_file ${jfr_file%.jfr}-cpu.html
  # print the produced ones
  echo ${jfr_file%.jfr}-cpu.html
done
