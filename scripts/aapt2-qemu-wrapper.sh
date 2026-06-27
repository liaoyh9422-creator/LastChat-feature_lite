#!/usr/bin/env bash
set -u

CANDIDATES=(
  "/root/.gradle/caches/8.14/transforms/f19b2ca04ff56827c3bd7bc8dee5df71/transformed/aapt2-8.13.0-13719691-linux/aapt2"
  "/root/.gradle/caches/8.14/transforms"/*/transformed/aapt2-8.13.0-13719691-linux/aapt2
)

AAPT2_BIN=""
for candidate in "${CANDIDATES[@]}"; do
  if [ -f "$candidate" ]; then
    AAPT2_BIN="$candidate"
    break
  fi
done

if [ -z "$AAPT2_BIN" ]; then
  echo "aapt2 override wrapper: no cached aapt2 binary found" >&2
  exit 1
fi

mkdir -p /lib64
ln -sf /usr/x86_64-linux-gnu/lib64/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2

exec /usr/bin/qemu-x86_64-static -L /usr/x86_64-linux-gnu "$AAPT2_BIN" "$@"
