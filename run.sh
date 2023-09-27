#!/usr/bin/env bash

scala-cli run . \
	--main-class LiveServer \
	-- \
	--entry-file=mock-index.html \
	--verbose
