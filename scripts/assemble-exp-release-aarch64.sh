#!/usr/bin/env bash
set -u

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
AAPT_DIR="${HOME}/.gradle/caches/8.14/transforms/f19b2ca04ff56827c3bd7bc8dee5df71/transformed/aapt2-8.13.0-13719691-linux"
AAPT_BIN="$AAPT_DIR/aapt2.bin"
AAPT_WRAPPER="$AAPT_DIR/aapt2"
LOG_DIR="/sdcard/Download/Operit/engine_logs"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="$LOG_DIR/assembleExpRelease_aarch64_${TS}.log"

mkdir -p "$LOG_DIR"

if [ ! -f "$AAPT_BIN" ] && [ -f "$AAPT_WRAPPER" ]; then
  mv "$AAPT_WRAPPER" "$AAPT_BIN"
fi

mkdir -p /lib64
ln -sf /usr/x86_64-linux-gnu/lib64/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2

cat > "$AAPT_WRAPPER" <<'EOF'
#!/bin/sh
exec /usr/bin/qemu-x86_64-static -L /usr/x86_64-linux-gnu "$(dirname "$0")/aapt2.bin" "$@"
EOF
chmod +x "$AAPT_WRAPPER"

{
  echo "probe=gradle_release_build"
  echo "probe_target=:app:assembleExpRelease"
  echo "mode=aarch64_aapt2_wrapper"
  echo "root=$ROOT_DIR"
  echo "aapt_wrapper=$AAPT_WRAPPER"
  echo "aapt_bin=$AAPT_BIN"
  echo "log_file=$LOG_FILE"
  echo "--- aapt2 version ---"
  "$AAPT_WRAPPER" version
  echo "--- gradle build ---"
  cd "$ROOT_DIR"
  bash ./gradlew :app:assembleExpRelease --no-daemon --stacktrace
} 2>&1 | tee "$LOG_FILE"

echo "LOG_FILE=$LOG_FILE"
