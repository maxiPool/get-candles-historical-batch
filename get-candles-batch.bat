echo "Running get candles batch job" > batch-job-output.txt && ^
.\mvnw package -DskipTests=true >> batch-job-output.txt && ^
java -jar .\target\get-candles-historical-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=local >> batch-job-output.txt && ^
echo "DONE!" >> batch-job-output.txt