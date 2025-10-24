.PHONY: help lint test test-all cover cover-check build run verify clean wrapper dist

PLUGIN_DIR := intellij-latex-plugin
GRADLEW := $(PLUGIN_DIR)/gradlew
# Use wrapper if present, otherwise fall back to system Gradle
GRADLE := $(if $(wildcard $(GRADLEW)),$(GRADLEW),gradle)

.DEFAULT_GOAL := help

help:()
	@echo "Targets:"
	@echo "  lint     - Run Gradle 'check' (code quality/tests)"
	@echo "  test     - Run unit tests"
	@echo "  test-all - Run all tests, including LightPlatform"
	@echo "  cover    - Generate JaCoCo coverage report"
	@echo "  cover-check - Verify coverage >= 80%"
	@echo "  build    - Build plugin distribution ZIP"
	@echo "  run      - Launch sandbox IDE with the plugin"
	@echo "  verify   - Verify plugin against IDE versions"
	@echo "  clean    - Clean build outputs"
	@echo "  wrapper  - Generate/update Gradle wrapper in plugin dir"
	@echo "  dist     - List built plugin ZIP(s)"

lint:
	$(GRADLE) -p $(PLUGIN_DIR) check

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

build:
	$(GRADLE) -p $(PLUGIN_DIR) buildPlugin
	@mkdir -p build
	@cp -f $(PLUGIN_DIR)/build/distributions/*.zip build/

run:
	$(GRADLE) -p $(PLUGIN_DIR) runIde

verify:
	@echo "Note: verifyPlugin may download IDEs to test compatibility"
	$(GRADLE) -p $(PLUGIN_DIR) verifyPlugin

clean:
	$(GRADLE) -p $(PLUGIN_DIR) clean

wrapper:
	gradle -p $(PLUGIN_DIR) wrapper

dist:
	@ls -1 $(PLUGIN_DIR)/build/distributions/*.zip 2>/dev/null || \
		echo "No distributions found. Run 'make build' first."
