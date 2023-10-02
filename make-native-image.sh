#!/usr/bin/env bash

scala-cli --power package --native-image . \
	--main-class LiveServer \
	-o live-server \
  --java-home "$JAVA_HOME" \
	-- \
  -H:-CheckToolchain \
	--initialize-at-build-time \
	--no-fallback \
	--enable-http \
	--enable-https
