#!/usr/bin/env bash
# OpenWarden bootstrap: install + verify dev environment.
# Idempotent — safe to re-run.
set -euo pipefail

cd "$(dirname "$0")/.."

# Load overrides if present
[[ -f .bootstrap.env ]] && source .bootstrap.env

JDK_VERSION="${JDK_VERSION:-21}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-35}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-35;google_apis;x86_64}"
AVD_NAME="${AVD_NAME:-openwarden-pixel7-api35}"

INFO() { echo "[bootstrap] $*"; }
WARN() { echo "[bootstrap] WARN: $*" >&2; }
FAIL() { echo "[bootstrap] FAIL: $*" >&2; exit 1; }

detect_os() {
  case "$(uname -s)" in
    Darwin) echo "macos" ;;
    Linux) echo "linux" ;;
    MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
    *) echo "unknown" ;;
  esac
}
OS="$(detect_os)"
INFO "Detected OS: $OS"

# ---------- JDK 21 ----------
check_jdk() {
  if command -v java >/dev/null 2>&1; then
    local ver
    ver=$(java -version 2>&1 | head -1 | awk -F'"' '{print $2}' | cut -d. -f1)
    if [[ "$ver" -ge "$JDK_VERSION" ]]; then
      INFO "JDK: OK ($(java -version 2>&1 | head -1))"
      return 0
    fi
  fi
  return 1
}
install_jdk() {
  INFO "Installing JDK $JDK_VERSION"
  case "$OS" in
    macos)
      command -v brew >/dev/null || FAIL "Install Homebrew first: https://brew.sh"
      brew install --cask temurin@"$JDK_VERSION"
      ;;
    linux)
      if command -v apt-get >/dev/null; then
        sudo apt-get update && sudo apt-get install -y "openjdk-${JDK_VERSION}-jdk"
      elif command -v dnf >/dev/null; then
        sudo dnf install -y "java-${JDK_VERSION}-openjdk-devel"
      else
        FAIL "Unknown Linux package manager. Install JDK $JDK_VERSION manually."
      fi
      ;;
    windows)
      command -v winget >/dev/null || FAIL "winget required on Windows."
      winget install --id EclipseAdoptium.Temurin."$JDK_VERSION".JDK -e --accept-source-agreements --accept-package-agreements
      ;;
    *) FAIL "Unsupported OS for auto-install" ;;
  esac
}
check_jdk || install_jdk
check_jdk || FAIL "JDK still not detected after install — restart shell + re-run"

# ---------- Android SDK + platform-tools ----------
check_adb() {
  if command -v adb >/dev/null 2>&1; then
    INFO "adb: OK ($(adb --version | head -1))"
    return 0
  fi
  return 1
}
install_android_platform_tools() {
  INFO "Installing Android platform-tools"
  case "$OS" in
    macos)
      brew install --cask android-platform-tools
      ;;
    linux)
      if command -v apt-get >/dev/null; then
        sudo apt-get install -y android-tools-adb android-tools-fastboot
      else
        WARN "Manual install: https://developer.android.com/tools/releases/platform-tools"
      fi
      ;;
    windows)
      winget install --id Google.PlatformTools -e --accept-source-agreements --accept-package-agreements
      ;;
  esac
}
check_adb || install_android_platform_tools
check_adb || WARN "adb still not detected — may need shell restart"

# ---------- Android SDK + AVD ----------
check_sdk() {
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    INFO "ANDROID_HOME: $ANDROID_HOME"
    return 0
  fi
  if [[ -d "$HOME/Android/Sdk" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
    INFO "Detected ANDROID_HOME=$ANDROID_HOME"
    return 0
  fi
  if [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
    INFO "Detected ANDROID_HOME=$ANDROID_HOME"
    return 0
  fi
  if [[ -d "$LOCALAPPDATA/Android/Sdk" ]]; then
    export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
    INFO "Detected ANDROID_HOME=$ANDROID_HOME"
    return 0
  fi
  return 1
}
check_sdk || WARN "ANDROID_HOME not set. Install Android Studio + set ANDROID_HOME."

# ---------- system image + AVD ----------
if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  INFO "Installing system image $SYSTEM_IMAGE"
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "$SYSTEM_IMAGE" 2>&1 | tail -3 || \
    WARN "sdkmanager failed; install system image manually via Android Studio"

  if "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "$AVD_NAME"; then
    INFO "AVD $AVD_NAME: already exists"
  else
    INFO "Creating AVD $AVD_NAME"
    echo "no" | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
      -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_7 || WARN "AVD creation failed; create manually"
  fi
else
  WARN "sdkmanager not at expected path; install Android Studio + cmdline-tools"
fi

# ---------- optional tools ----------
install_optional() {
  local tool="$1" cmd="$2" brew_pkg="$3" winget_id="$4" apt_pkg="$5"
  if command -v "$cmd" >/dev/null 2>&1; then
    INFO "$tool: OK"
    return 0
  fi
  INFO "Installing $tool"
  case "$OS" in
    macos) brew install "$brew_pkg" || WARN "$tool install failed" ;;
    linux) sudo apt-get install -y "$apt_pkg" 2>/dev/null || WARN "$tool install failed" ;;
    windows) winget install --id "$winget_id" -e --accept-source-agreements --accept-package-agreements || WARN "$tool install failed" ;;
  esac
}

install_optional "gh"        "gh"        "gh"           "GitHub.cli"     "gh"
install_optional "jq"        "jq"        "jq"           "jqlang.jq"      "jq"
install_optional "ktlint"    "ktlint"    "ktlint"       "Pinterest.Ktlint" "ktlint"

# detekt is a Gradle plugin, not a CLI tool. Skip CLI install.
# swiftlint only matters on macOS for iOS work
if [[ "$OS" == "macos" ]]; then
  install_optional "swiftlint" "swiftlint" "swiftlint" "" ""
fi

# Codex CLI
if command -v codex >/dev/null 2>&1; then
  INFO "codex: OK"
else
  if command -v npm >/dev/null 2>&1; then
    INFO "Installing OpenAI Codex CLI"
    npm install -g @openai/codex || WARN "codex install failed (npm package may have moved)"
  else
    WARN "npm not available; install Node.js to enable codex CLI"
  fi
fi

# ---------- Pre-commit hooks (placeholder until Phase 1 scaffolds Gradle) ----------
mkdir -p .git/hooks
cat > .git/hooks/pre-commit <<'HOOK'
#!/usr/bin/env bash
# OpenWarden pre-commit hook
# Phase 0: just check no secrets / large files. Phase 1+ adds lint + test.
set -e
if git diff --cached --name-only | xargs grep -l "BEGIN.*PRIVATE KEY\|BIP39\|recovery.phrase" 2>/dev/null; then
  echo "REFUSED: looks like a secret in staged files"
  exit 1
fi
exit 0
HOOK
chmod +x .git/hooks/pre-commit

# ---------- summary ----------
INFO ""
INFO "Bootstrap complete. Next steps:"
INFO "  1. Restart your shell if you installed JDK / adb"
INFO "  2. Verify: ./scripts/verify-env.sh"
INFO "  3. Once Phase 1 lands the Gradle build: ./gradlew build"
INFO "  4. For emulator E2E: ./scripts/test-emulator.sh"
