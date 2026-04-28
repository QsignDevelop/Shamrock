#!/bin/bash
docker build -t shamrock-build .
docker run --rm -v $(pwd)/app/build/outputs:/outputs shamrock-build
ls -la app/build/outputs/apk/*/debug/ 2>/dev/null || ls -la app/build/outputs/