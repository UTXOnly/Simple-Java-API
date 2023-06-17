#!/bin/bash

# Set project information
GROUP_ID="JavaTests"
ARTIFACT_ID="DbTestsProj"
VERSION="1.0.0"

# Create a new Maven project
mvn archetype:generate -DgroupId=${GROUP_ID} -DartifactId=${ARTIFACT_ID} -Dversion=${VERSION} \
    -DarchetypeArtifactId=maven-archetype-quickstart -DinteractiveMode=false


cd ${ARTIFACT_ID}

# Remove the generated Java file and test directory
rm src/main/java/${GROUP_ID}/App.java
rm -r src/test

# Copy the DBClient.java file to the correct directory and package name
cp ../DbClient.java ./src/main/java/${GROUP_ID}/

# Copy the pom.xml file to the project directory
cp ../../pom.xml ./
echo $PWD
# Build the project package
mvn package
