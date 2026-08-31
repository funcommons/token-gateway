#!/usr/bin/env bash
# Download mock image assets for the demo.
# - Course covers: picsum.photos (seed-based, deterministic)
# - Avatars: dicebear (avataaars style)
# - Banners: picsum.photos
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG="$ROOT/public/images"
COURSES="$IMG/courses"
AVATARS="$IMG/avatars"
BANNERS="$IMG/banners"

mkdir -p "$COURSES" "$AVATARS" "$BANNERS"

dl() {
  local url="$1"
  local out="$2"
  if [ -f "$out" ] && [ -s "$out" ]; then
    return 0
  fi
  curl -sL --max-time 30 -H "User-Agent: Mozilla/5.0" -o "$out" "$url" || {
    echo "FAILED: $url"
    return 1
  }
  local size=$(stat -f%z "$out" 2>/dev/null || stat -c%s "$out")
  if [ "$size" -lt 200 ]; then
    echo "TOO SMALL ($size): $url -> $out"
    return 1
  fi
  echo "OK: $out ($size bytes)"
}

echo "=== Downloading course covers (20) ==="
for i in $(seq 1 20); do
  dl "https://picsum.photos/seed/course-$i/640/360" "$COURSES/$i.jpg"
done

echo "=== Downloading avatars (30) ==="
for i in $(seq 1 30); do
  dl "https://api.dicebear.com/7.x/avataaars/png?seed=user-$i" "$AVATARS/$i.png"
done

echo "=== Downloading banners (5) ==="
for i in $(seq 1 5); do
  dl "https://picsum.photos/seed/banner-$i/1920/480" "$BANNERS/$i.jpg"
done

echo "=== Done ==="
