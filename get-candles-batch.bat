echo "Running get candles batch job" && ^
java -jar .\target\get-candles-historical-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=local && ^
echo "DONE!"