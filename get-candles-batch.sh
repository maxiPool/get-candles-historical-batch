#!/bin/bash
# Runs the deployed jar once. Extra arguments are passed to the app, e.g. for a backfill dry run:
#   ./get-candles-batch.sh --app.only-instruments=EUR_USD --app.dry-run=true
# Cron calls it through bin/cron-wrapper.sh, which emails on a non-zero exit.
export JAVA_HOME="/home/max/.sdkman/candidates/java/21.0.9-tem"
export PATH="$JAVA_HOME/bin:$PATH"

cd /home/max/dev/projects/get-candles-historical-batch || exit 1
echo "Running get candles batch job"
java -jar ./deployment/get-candles-historical-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=local "$@"
code=$?
echo "DONE! (exit $code)"
exit $code
