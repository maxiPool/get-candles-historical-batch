echo "Running get candles batch job" && ^
java -jar .\deployment\get-candles-historical-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=local && ^
echo "DONE!"