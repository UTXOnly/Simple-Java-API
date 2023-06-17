#!/bin/bash
java -javaagent:./dd-java-agent.jar \
     -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=8001 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -jar DbTestsProj-1.0.0.jar \
     -XX:FlightRecorderOptions=stackdepth=256 \
     -Ddd.logs.injection=true \
     -Ddd.service=java_app \
     -Ddd.env=staging \
     -Ddd.version=1.0