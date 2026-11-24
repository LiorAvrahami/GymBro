#!/bin/bash
# The build that GitHub runs (.github/workflows/build.yml): core's tests and the signed release APK, in one Gradle run.
# When the package sites did not deliver ("Could not resolve ..."), the build is run again, up to three times in all
# (as in the 6SteP project). Any other failure stops at once.
set -o pipefail
for attempt in 1 2 3; do
  if gradle :core:test assembleRelease --no-daemon --stacktrace 2>&1 | tee build.log; then exit 0; fi
  if [ "$attempt" = 3 ] || ! grep -q "Could not resolve all artifacts\|Could not resolve all files\|Could not resolve all dependencies\|Could not GET \|Could not HEAD " build.log; then exit 1; fi
  echo "::warning::The package sites did not deliver (attempt $attempt of 3): the build is run again in 30 s"
  sleep 30
done
