#!/bin/bash
# Build and deploy HEAD (bin/deploy.sh), then run the batch once.
cd /home/max/dev/projects/get-candles-historical-batch || exit 1
./bin/deploy.sh && ./get-candles-batch.sh "$@"
