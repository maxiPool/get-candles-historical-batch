#!/bin/bash
export JAVA_HOME="/home/max/.sdkman/candidates/java/21.0.9-tem"
export PATH="$JAVA_HOME/bin:$PATH"

cd /home/max/dev/projects/get-candles-historical-batch
echo "Running get candles batch job" && \
./mvnw clean package -DskipTests=true && \
mkdir -p deployment && \
cp ./target/get-candles-historical-batch-0.0.1-SNAPSHOT.jar ./deployment/get-candles-historical-batch-0.0.1-SNAPSHOT.jar && \
java -jar ./deployment/get-candles-historical-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=local && \
echo "DONE!"
