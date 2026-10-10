#!/usr/bin/env sh
# Mirror phone A on the laptop over USB (JDG-01); see mirror.ps1 for the Windows version.
#
#   laptop/mirror.sh            # mirror only
#   laptop/mirror.sh --record   # mirror and record the backup video to recordings/demo-<time>.mp4
set -eu

record=""
[ "${1:-}" = "--record" ] && record=1

dir="$(cd "$(dirname "$0")/.." && pwd)/recordings"
opts="--window-title=FriendOrFoe-phone-A --no-audio --stay-awake --show-touches --max-size=1920 --video-bit-rate=8M"

if [ -n "$record" ]; then
  mkdir -p "$dir"
  file="$dir/demo-$(date +%Y%m%d-%H%M%S).mp4"
  echo "Recording to $file (stop with Ctrl+C or by closing the window)"
  # shellcheck disable=SC2086
  exec scrcpy $opts --record="$file"
fi
# shellcheck disable=SC2086
exec scrcpy $opts
