#!/bin/bash

# Set project information
GROUP_ID="JavaTests"
ARTIFACT_ID="DbTestsProj"
VERSION="1.0.0"

# Create a new Maven project
mvn archetype:generate -DgroupId=${GROUP_ID} -DartifactId=${ARTIFACT_ID} -Dversion=${VERSION} \
    -DarchetypeArtifactId=maven-archetype-quickstart -DinteractiveMode=false

cd ${ARTIFACT_ID}

rm src/main/java/${GROUP_ID}/App.java
rm -r src/test

cp ../RandomApi.java ./src/main/java/${GROUP_ID}/
cp ../main_pom/pom.xml ./

mvn package
cd target
cp ../../start.sh ./
wget -O dd-java-agent.jar https://dtdg.co/latest-java-tracer
cd ..
cp ../.env ./target/
echo $PWD
