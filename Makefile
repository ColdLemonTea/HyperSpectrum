.DEFAULT_GOAL := help
.PHONY: help check release

JAVA_HOME ?= /opt/android-studio/jbr
ANDROID_HOME ?= $(HOME)/Android/Sdk
export JAVA_HOME ANDROID_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

GRADLE := ./gradlew --no-daemon --no-configuration-cache --console=plain

help:
	@printf '%s\n' 'make check    Run tests, lint and debug build' 'make release  Build with 1Password-backed signing'

check:
	$(GRADLE) testDebugUnitTest assembleDebug lintDebug

release:
	op run --env-file=signing.env -- $(GRADLE) testDebugUnitTest assembleDebug lintDebug assembleRelease
