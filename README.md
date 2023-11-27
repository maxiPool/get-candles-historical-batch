### Batch job to get historical candles

Dedicated project for the batch job; faster startup, fewer dependencies.

#### Build

`.\mvnw clean package -DskipTests=true`

#### Run

`java -jar .\target\get-candles-historical-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`
