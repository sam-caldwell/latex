.PHONY: help version lint test test-all cover cover-check coverage build run verify clean wrapper dist tag/patch tag/minor tag/major tag/pre

PLUGIN_DIR := intellij-latex-plugin
GRADLEW := $(PLUGIN_DIR)/gradlew
# Use wrapper if present, otherwise fall back to system Gradle
GRADLE := $(if $(wildcard $(GRADLEW)),$(GRADLEW),gradle)
# Shared settings
VERSION_FILE := $(PLUGIN_DIR)/build.gradle.kts
SEMVER_RE := '^[0-9]+\.[0-9]+\.[0-9]+$$'

.DEFAULT_GOAL := help

help:()
	@echo "Targets:"
	@echo "  version  - Print current plugin version"
	@echo "  lint     - Run Gradle 'check' (code quality/tests)"
	@echo "  test     - Run unit tests"
	@echo "  test-all - Run all tests, including LightPlatform"
	@echo "  cover    - Generate JaCoCo coverage report"
	@echo "  cover-check - Verify coverage >= 80%"
	@echo "  coverage - Run tests, report coverage %, and gate >=80%"
	@echo "  build    - Build plugin distribution ZIP"
	@echo "  run      - Launch sandbox IDE with the plugin"
	@echo "  verify   - Verify plugin against IDE versions"
	@echo "  clean    - Clean build outputs"
	@echo "  wrapper  - Generate/update Gradle wrapper in plugin dir"
	@echo "  dist     - List built plugin ZIP(s)"
	@echo "  tag/patch- Bump patch, tag and push"
	@echo "  tag/minor- Bump minor (reset patch), tag and push"
	@echo "  tag/major- Bump major (reset minor/patch), tag and push"
	@echo "  tag/pre  - Create pre-release tag vX.Y.Z-<short>, push"

lint:
	$(GRADLE) -p $(PLUGIN_DIR) -DenableLightTests=true check

test:
	# Run all tests (unit + LightPlatform) with coverage gating
	$(GRADLE) -p $(PLUGIN_DIR) \
	  -DenableLightTests=true \
	  test lightTest jacocoTestReport jacocoTestCoverageVerification

test-all: test

cover:
	$(GRADLE) -p $(PLUGIN_DIR) -DenableLightTests=true jacocoTestReport

cover-check:
	$(GRADLE) -p $(PLUGIN_DIR) -DenableLightTests=true jacocoTestCoverageVerification

# Convenience: run tests, generate coverage, print %, and gate >=80%
coverage:
	@set -e; \
	$(GRADLE) -p $(PLUGIN_DIR) -DenableLightTests=true test jacocoTestReport jacocoTestCoverageVerification >/dev/null; \
	XML="$(PLUGIN_DIR)/build/reports/jacoco/test/jacocoTestReport.xml"; \
	if [ ! -f "$$XML" ]; then echo "Coverage XML not found: $$XML"; exit 1; fi; \
	PCT=$$(awk -F '"' '/counter type="INSTRUCTION"/{missed+=$$6;covered+=$$8} END{ if (missed+covered>0) printf "%.2f", (100*covered/(missed+covered)); else print "0.00" }' "$$XML"); \
	echo "Coverage: $$PCT%"; \
	awk -v p="$$PCT" 'BEGIN { if (p+0.0 >= 80.0) { exit 0 } else { exit 1 } }' >/dev/null || { echo "Coverage gate failed (< 80%)"; exit 1; }

build:
	@# Ensure Gradle wrapper is executable if present
	@if [ -f "$(GRADLEW)" ]; then chmod +x "$(GRADLEW)"; fi
	$(GRADLE) -p $(PLUGIN_DIR) --no-daemon --stacktrace buildPlugin
	@mkdir -p build
	@cp -f $(PLUGIN_DIR)/build/distributions/*.zip build/

run:
	$(GRADLE) -p $(PLUGIN_DIR) runIde

# Developer-friendly alias: `make runIde` launches the sandbox IDE
runIde: run

verify:
	@echo "Note: verifyPlugin may download IDEs to test compatibility"
	$(GRADLE) -p $(PLUGIN_DIR) verifyPlugin

clean:
	$(GRADLE) -p $(PLUGIN_DIR) clean
	@rm -rf build
	@rm -rf $(PLUGIN_DIR)/build
	@mkdir -p build

wrapper:
	gradle -p $(PLUGIN_DIR) wrapper

dist:
	@ls -1 $(PLUGIN_DIR)/build/distributions/*.zip 2>/dev/null || \
		echo "No distributions found. Run 'make build' first."

# Print the current semantic version from build.gradle.kts
version:
	@set -e; \
	FILE="$(VERSION_FILE)"; \
	if [ ! -f "$$FILE" ]; then echo "Not found: $$FILE"; exit 1; fi; \
	CUR=$$(sed -E -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"([0-9]+\.[0-9]+\.[0-9]+)"/\1/p' "$$FILE"); \
	if [ -z "$$CUR" ]; then echo "Could not determine current version from $$FILE"; exit 1; fi; \
	echo "version: $$CUR"

# Bump patch version in build.gradle.kts and tag the repo
tag/patch:
	@set -e; \
	# Require green lint, tests, coverage and build
	if [ -f "$(GRADLEW)" ]; then chmod +x "$(GRADLEW)"; fi; \
	$(GRADLE) -p $(PLUGIN_DIR) --no-daemon --stacktrace -DenableLightTests=true clean check buildPlugin; \
	FILE="$(VERSION_FILE)"; \
	if [ ! -f "$$FILE" ]; then echo "Not found: $$FILE"; exit 1; fi; \
	CUR=$$(sed -E -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"([0-9]+\.[0-9]+\.[0-9]+)"/\1/p' "$$FILE"); \
	if [ -z "$$CUR" ]; then echo "Could not determine current version from $$FILE"; exit 1; fi; \
	if ! echo "$$CUR" | grep -Eq $(SEMVER_RE); then echo "Invalid semantic version: $$CUR"; exit 1; fi; \
	MAJOR=$$(echo "$$CUR" | cut -d. -f1); \
	MINOR=$$(echo "$$CUR" | cut -d. -f2); \
	PATCHV=$$(echo "$$CUR" | cut -d. -f3); \
	NEWPATCH=$$((PATCHV+1)); \
	NEWVER="$$MAJOR.$$MINOR.$$NEWPATCH"; \
	echo "Bumping version $$CUR -> $$NEWVER"; \
	sed -E -i.bak 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"[0-9]+\.[0-9]+\.[0-9]+"/version = "'"$$NEWVER"'"/' "$$FILE"; \
	rm -f "$$FILE.bak"; \
	git add -A; \
	git commit -m "chore: bump version to $$NEWVER" || true; \
	if git rev-parse -q --verify "refs/tags/v$$NEWVER" >/dev/null; then \
	  echo "Tag v$$NEWVER already exists"; \
	else \
	  git tag -a "v$$NEWVER" -m "Release v$$NEWVER"; \
	  echo "Created tag v$$NEWVER"; \
	fi; \
	git push origin HEAD; \
	git push origin "v$$NEWVER"; \
	echo "Pushed branch and tag: v$$NEWVER"; \
	echo "Done: version $$NEWVER"

# Create a pre-release tag vX.Y.Z-<git short hash> and push
tag/pre:
	@set -e; \
	# Require green lint, tests, coverage and build
	if [ -f "$(GRADLEW)" ]; then chmod +x "$(GRADLEW)"; fi; \
	$(GRADLE) -p $(PLUGIN_DIR) --no-daemon --stacktrace -DenableLightTests=true clean check buildPlugin; \
	FILE="$(VERSION_FILE)"; \
	if [ ! -f "$$FILE" ]; then echo "Not found: $$FILE"; exit 1; fi; \
	CUR=$$(sed -E -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"([0-9]+\.[0-9]+\.[0-9]+)"/\1/p' "$$FILE"); \
	if [ -z "$$CUR" ]; then echo "Could not determine current version from $$FILE"; exit 1; fi; \
	if ! echo "$$CUR" | grep -Eq $(SEMVER_RE); then echo "Invalid semantic version in build.gradle.kts: $$CUR"; exit 1; fi; \
	SHORT=$$(git rev-parse --short HEAD); \
	TAG="v$$CUR-$$SHORT"; \
	if git rev-parse -q --verify "refs/tags/$$TAG" >/dev/null; then \
	  echo "Tag $$TAG already exists"; exit 1; \
	fi; \
	git add -A; \
	git commit -m "chore: pre-release $$TAG" || true; \
	git tag -a "$$TAG" -m "Pre-release $$TAG"; \
	git push origin HEAD; \
	git push origin "$$TAG"; \
	echo "Pushed pre-release tag: $$TAG"

# Bump minor version (X.Y.Z -> X.(Y+1).0) and tag the repo
tag/minor:
	@set -e; \
	# Require green lint, tests, coverage and build
	if [ -f "$(GRADLEW)" ]; then chmod +x "$(GRADLEW)"; fi; \
	$(GRADLE) -p $(PLUGIN_DIR) --no-daemon --stacktrace -DenableLightTests=true clean check buildPlugin; \
	FILE="$(VERSION_FILE)"; \
	if [ ! -f "$$FILE" ]; then echo "Not found: $$FILE"; exit 1; fi; \
	CUR=$$(sed -E -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"([0-9]+\.[0-9]+\.[0-9]+)"/\1/p' "$$FILE"); \
	if [ -z "$$CUR" ]; then echo "Could not determine current version from $$FILE"; exit 1; fi; \
	MAJOR=$$(echo "$$CUR" | cut -d. -f1); \
	MINOR=$$(echo "$$CUR" | cut -d. -f2); \
	NEWMINOR=$$((MINOR+1)); \
	NEWVER="$$MAJOR.$$NEWMINOR.0"; \
	if ! echo "$$CUR" | grep -Eq $(SEMVER_RE); then echo "Invalid semantic version: $$CUR"; exit 1; fi; \
	echo "Bumping version $$CUR -> $$NEWVER"; \
	sed -E -i.bak 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"[0-9]+\.[0-9]+\.[0-9]+"/version = "'"$$NEWVER"'"/' "$$FILE"; \
	rm -f "$$FILE.bak"; \
	git add -A; \
	git commit -m "chore: bump version to $$NEWVER" || true; \
	if git rev-parse -q --verify "refs/tags/v$$NEWVER" >/dev/null; then \
	  echo "Tag v$$NEWVER already exists"; \
	else \
	  git tag -a "v$$NEWVER" -m "Release v$$NEWVER"; \
	  echo "Created tag v$$NEWVER"; \
	fi; \
	git push origin HEAD; \
	git push origin "v$$NEWVER"; \
	echo "Pushed branch and tag: v$$NEWVER"; \
	echo "Done: version $$NEWVER"

# Bump major version (X.Y.Z -> (X+1).0.0) and tag the repo
tag/major:
	@set -e; \
	# Require green lint, tests, coverage and build
	if [ -f "$(GRADLEW)" ]; then chmod +x "$(GRADLEW)"; fi; \
	$(GRADLE) -p $(PLUGIN_DIR) --no-daemon --stacktrace -DenableLightTests=true clean check buildPlugin; \
	FILE="$(VERSION_FILE)"; \
	if [ ! -f "$$FILE" ]; then echo "Not found: $$FILE"; exit 1; fi; \
	CUR=$$(sed -E -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"([0-9]+\.[0-9]+\.[0-9]+)"/\1/p' "$$FILE"); \
	if [ -z "$$CUR" ]; then echo "Could not determine current version from $$FILE"; exit 1; fi; \
	MAJOR=$$(echo "$$CUR" | cut -d. -f1); \
	NEWMAJOR=$$((MAJOR+1)); \
	NEWVER="$$NEWMAJOR.0.0"; \
	if ! echo "$$CUR" | grep -Eq $(SEMVER_RE); then echo "Invalid semantic version: $$CUR"; exit 1; fi; \
	echo "Bumping version $$CUR -> $$NEWVER"; \
	sed -E -i.bak 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"[0-9]+\.[0-9]+\.[0-9]+"/version = "'"$$NEWVER"'"/' "$$FILE"; \
	rm -f "$$FILE.bak"; \
	git add -A; \
	git commit -m "chore: bump version to $$NEWVER" || true; \
	if git rev-parse -q --verify "refs/tags/v$$NEWVER" >/dev/null; then \
	  echo "Tag v$$NEWVER already exists"; \
	else \
	  git tag -a "v$$NEWVER" -m "Release v$$NEWVER"; \
	  echo "Created tag v$$NEWVER"; \
	fi; \
	git push origin HEAD; \
	git push origin "v$$NEWVER"; \
	echo "Pushed branch and tag: v$$NEWVER"; \
	echo "Done: version $$NEWVER"
