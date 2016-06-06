#!/bin/bash
export JAVA_HOME=$JAVA8_HOME

echo Building projects using gradle...
./gradlew build
