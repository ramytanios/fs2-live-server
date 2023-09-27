#!/usr/bin/env bash

scala-cli --power package --native-image . \
	--main-class LiveServer \
	-o live-server \
	--jvm 17 \
	-- \
	-H:IncludeResources=".*" \
	--initialize-at-build-time \
	--no-fallback \
	--enable-http \
	--enable-https
