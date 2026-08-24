#!/usr/bin/env bash
# Pre-generates the world the page ships. The first thing a visitor waits for is "Preparing spawn
# area", which takes minutes on CheerpJ's single cooperative thread, so we generate the spawn region
# once on a real JDK 8 (same downgraded jars the browser runs) and ship it as web/public/world.zip.
# The launcher unpacks it into the browser's filesystem on first boot.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
J8="$ROOT/work/jdk8"
JARS="$ROOT/web/public/jars"
RUN="$ROOT/work/worldgen"
SEED="${SEED:-lodeway}"
BOOT_SECONDS="${BOOT_SECONDS:-90}"

[ -x "$J8/bin/java" ] || { echo "run scripts/build-jar.sh first (needs work/jdk8)" >&2; exit 1; }
[ -d "$JARS" ] || { echo "run scripts/build-jar.sh first (needs web/public/jars)" >&2; exit 1; }

rm -rf "$RUN" && mkdir -p "$RUN"
cd "$RUN"
echo "eula=true" > eula.txt
# the odd port keeps this from colliding with anything already using the default 25565
printf 'view-distance=2\nsimulation-distance=2\nlevel-seed=%s\nonline-mode=false\nserver-port=25599\n' "$SEED" > server.properties

echo "== booting the server to generate the spawn region (about $((BOOT_SECONDS + 30))s)"
# same order as the page's classpath: the server jar must come before the libraries, or the
# standalone logging jar's older LogUtils shadows the one Paper was compiled against
CP="$(sed "s|^jars/|$JARS/|" "$ROOT/web/public/classpath.txt" | grep -v launcher.jar | tr '\n' ':')"
{ sleep "$BOOT_SECONDS"; echo stop; } | "$J8/bin/java" -Xmx3G -cp "$CP" org.bukkit.craftbukkit.Main nogui > boot.log 2>&1 || true
grep -q 'Done (' boot.log || { echo "the server never reached Done; see $RUN/boot.log" >&2; exit 1; }

# session.lock is per-boot and console.log is noise; neither belongs in the shipped world.
# the nether and end only exist once something touched them, so zip whatever is there.
rm -f world*/session.lock console.log
DIRS="world"
for d in world_nether world_the_end; do [ -d "$d" ] && DIRS="$DIRS $d"; done
rm -f "$ROOT/web/public/world.zip"
zip -qr "$ROOT/web/public/world.zip" $DIRS -x '*/session.lock'
echo "== wrote web/public/world.zip ($(du -h "$ROOT/web/public/world.zip" | cut -f1 | tr -d ' '))"
