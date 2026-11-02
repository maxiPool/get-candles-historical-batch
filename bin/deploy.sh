#!/usr/bin/env bash
# Build the jar from the committed HEAD (tests included), embed the commit for the stale-jar check, and
# install it as deployment/get-candles-historical-batch-0.0.1-SNAPSHOT.jar (the jar cron runs).
# The previous jar is kept as deployment/previous-<timestamp>.jar for rollback.
# Run from the main checkout: the jar packages src/main/resources/application-local.yml (credentials).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
export JAVA_HOME="${JAVA_HOME:-/home/max/.sdkman/candidates/java/21.0.9-tem}"
export PATH="$JAVA_HOME/bin:$PATH"

die() { echo "deploy: $*" >&2; exit 1; }

[ -f src/main/resources/application-local.yml ] || die "src/main/resources/application-local.yml missing; deploy from the main checkout"
[ -z "$(git status --porcelain --untracked-files=no -- src pom.xml)" ] || die "uncommitted changes in src/ or pom.xml; commit them first"

COMMIT="$(git rev-parse HEAD)"
NAME=get-candles-historical-batch-0.0.1-SNAPSHOT.jar
echo "deploy: building $COMMIT"
./mvnw -q clean package -Dgit.commit="$COMMIT"

unzip -p "target/$NAME" BOOT-INF/classes/application.yml | grep -q "build-commit: \"$COMMIT\"" \
  || die "build commit not embedded in target/$NAME"

mkdir -p deployment
DST="deployment/$NAME"
if [ -f "$DST" ]; then
  cp -p "$DST" "deployment/previous-$(date +%Y%m%d-%H%M%S).jar"
fi
cp "target/$NAME" "$DST.tmp"
mv "$DST.tmp" "$DST"
printf '%s %s %s\n' "$COMMIT" "$(sha256sum "$DST" | cut -d' ' -f1)" "$(date --iso-8601=seconds)" > deployment/DEPLOYED
echo "deploy: installed $DST from $COMMIT"
