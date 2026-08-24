#!/usr/bin/env bash
# Builds the browser-ready Paper jars from source. Nothing from Mojang or PaperMC ships in this repo:
# this script clones Paper at a pinned commit, applies our patches, builds the server, then rewrites
# every jar to Java 8 bytecode (class file v52) with JVMDowngrader so CheerpJ can run it.
#
# Stages (each cached under work/; delete a stage dir to redo it):
#   deps   - JDK 8 (Azul, for verification + post-passes) and JVMDowngrader
#   paper  - clone PaperMC/Paper at $PAPER_COMMIT, apply patches, gradle build
#            (skipped when $PAPER_BUNDLER points at an existing bundler jar)
#   dg     - patch JVMDowngrader, downgrade all jars, build the api stub jar, post-passes
#   dist   - assemble web/public/jars + classpath.txt + launcher.jar
#
# Requirements: bash, git, curl, unzip, zip, rsync, and a JVM able to run Gradle/JVMDowngrader
# (Paper's Gradle toolchain downloads JDK 25 by itself).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/work"
PAPER_COMMIT=0bdc80db3271ce663457de11e45f74703c989f96
PAPER_VERSION=26.2
JVMDG_VERSION=2.0.1
JVMDG_URL="https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/jvmdowngrader/jvmdowngrader/$JVMDG_VERSION/jvmdowngrader-$JVMDG_VERSION-all.jar"
JOBS="${JOBS:-6}"

mkdir -p "$WORK"
log() { printf '\n== %s\n' "$*"; }

# ---- deps: JDK 8 + JVMDowngrader --------------------------------------------------------------------

jdk8_url() {
    local os arch
    case "$(uname -s)" in Darwin) os=macos ;; Linux) os=linux ;; *) echo "unsupported OS $(uname -s)" >&2; exit 1 ;; esac
    case "$(uname -m)" in arm64|aarch64) arch=aarch64 ;; x86_64|amd64) arch=x64 ;; *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;; esac
    curl -sf "https://api.azul.com/metadata/v1/zulu/packages/?java_version=8&os=$os&arch=$arch&archive_type=tar.gz&java_package_type=jdk&javafx_bundled=false&latest=true&release_status=ga" \
        | sed -n 's/.*"download_url": *"\([^"]*\)".*/\1/p' | head -1
}

stage_deps() {
    if [ ! -x "$WORK/jdk8/bin/java" ]; then
        log "fetching JDK 8 (Azul Zulu)"
        local url; url="$(jdk8_url)"
        [ -n "$url" ] || { echo "could not resolve a Zulu 8 download for this platform" >&2; exit 1; }
        curl -fL -o "$WORK/zulu8.tar.gz" "$url"
        rm -rf "$WORK/jdk8.tmp" && mkdir -p "$WORK/jdk8.tmp"
        tar xzf "$WORK/zulu8.tar.gz" -C "$WORK/jdk8.tmp" --strip-components=1
        # macOS bundles nest the JDK under Contents/Home
        if [ -d "$WORK/jdk8.tmp/Contents/Home" ]; then mv "$WORK/jdk8.tmp/Contents/Home" "$WORK/jdk8" && rm -rf "$WORK/jdk8.tmp"; else mv "$WORK/jdk8.tmp" "$WORK/jdk8"; fi
        rm -f "$WORK/zulu8.tar.gz"
    fi
    J8="$WORK/jdk8"
    "$J8/bin/java" -version 2>&1 | head -1

    if [ ! -f "$WORK/jvmdowngrader.jar" ]; then
        log "fetching JVMDowngrader $JVMDG_VERSION"
        curl -fL -o "$WORK/jvmdowngrader.jar" "$JVMDG_URL"
    fi
}

# ---- paper: clone, patch, build ---------------------------------------------------------------------

stage_paper() {
    if [ -n "${PAPER_BUNDLER:-}" ]; then
        log "using prebuilt bundler jar: $PAPER_BUNDLER"
        BUNDLER="$PAPER_BUNDLER"
        return
    fi
    local P="$WORK/paper"
    BUNDLER="$P/paper-server/build/libs/paper-bundler-$PAPER_VERSION.local-SNAPSHOT.jar"
    if [ -f "$BUNDLER" ]; then
        log "bundler jar already built (rm -rf work/paper to rebuild)"
        return
    fi
    if [ ! -d "$P/.git" ]; then
        log "cloning PaperMC/Paper @ $PAPER_COMMIT"
        git clone --no-checkout https://github.com/PaperMC/Paper "$P"
        git -C "$P" checkout -q "$PAPER_COMMIT"
    fi
    log "applying Paper's own patches (paperweight)"
    (cd "$P" && ./gradlew applyPatches)
    log "applying our patches"
    git -C "$P" apply --index --whitespace=nowarn "$ROOT/patches/paper.patch" 2>/dev/null || git -C "$P" apply --whitespace=nowarn "$ROOT/patches/paper.patch"
    rsync -a "$ROOT/sources/" "$P/"
    git -C "$P/paper-server/src/minecraft/java" apply --whitespace=nowarn "$ROOT/patches/minecraft.patch"
    log "building the server (this downloads JDK 25 through Gradle's toolchain on first run)"
    (cd "$P" && ./gradlew :paper-server:createBundlerJar)
    [ -f "$BUNDLER" ] || { echo "expected $BUNDLER after the build" >&2; exit 1; }
}

# ---- dg: downgrade everything to Java 8 -------------------------------------------------------------

patch_jvmdg() {
    # Two fixes ride inside the tool's nested META-INF/lib/java-api.jar:
    #  - Java11Downgrader: correct nest-mate accessor generation for interfaces (tools/jvmdg/)
    #  - four stub classes with behavioural fixes (tools/jvmdg-stubs/, see the README for what each fixes)
    local DG="$WORK/dg"
    log "patching JVMDowngrader"
    # the provider + stub classes live in the nested META-INF/lib/java-api.jar, so both the compile
    # classpath and the injection target are the extracted nested jar, not the all-jar itself
    rm -rf "$DG/nested" && mkdir -p "$DG/nested"
    (cd "$DG/nested" && unzip -q "$WORK/jvmdowngrader.jar" 'META-INF/lib/*')
    local TOOLCP="$WORK/jvmdowngrader.jar:$DG/nested/META-INF/lib/java-api.jar"
    rm -rf "$DG/toolpatch" && mkdir -p "$DG/toolpatch/classes"
    javac --release 8 -nowarn -cp "$TOOLCP" -d "$DG/toolpatch/classes" "$ROOT/tools/jvmdg/Java11Downgrader.java"
    "$J8/bin/javac" -nowarn -cp "$DG/api8.jar:$TOOLCP" -d "$DG/toolpatch/classes" \
        $(find "$ROOT/tools/jvmdg-stubs" -name '*.java')
    (cd "$DG/toolpatch/classes" && jar uf "$DG/nested/META-INF/lib/java-api.jar" xyz)
    cp "$WORK/jvmdowngrader.jar" "$DG/jvmdowngrader-patched.jar"
    (cd "$DG/nested" && jar uf "$DG/jvmdowngrader-patched.jar" META-INF/lib/java-api.jar)
    # the same four stub fixes go into the runtime stub jar
    (cd "$DG/toolpatch/classes" && jar uf "$DG/api8.jar" xyz)
}

stage_dg() {
    local DG="$WORK/dg"
    if [ -f "$DG/.done" ] && [ "$DG/.done" -nt "$BUNDLER" ]; then
        log "downgraded jars are current (rm work/dg/.done to redo)"
        return
    fi
    mkdir -p "$DG/in" "$DG/out" "$DG/logs"

    log "exploding the bundler jar"
    rm -rf "$DG/ex" && mkdir -p "$DG/ex"
    (cd "$DG/ex" && unzip -q "$BUNDLER" 'META-INF/versions/*' 'META-INF/libraries/*')
    rm -f "$DG/in"/*.jar
    cp "$DG/ex/META-INF/versions/$PAPER_VERSION/paper-$PAPER_VERSION.jar" "$DG/in/"
    find "$DG/ex/META-INF/libraries" -name '*.jar' -exec cp {} "$DG/in/" \;
    ls "$DG/in"/*.jar | tr '\n' ':' > "$DG/cp.txt"
    echo "   $(ls "$DG/in" | wc -l | tr -d ' ') input jars"

    log "building the Java 8 API stub jar"
    (cd "$DG" && java -jar "$WORK/jvmdowngrader.jar" -c 52 debug downgradeApi api8.jar)

    patch_jvmdg

    log "downgrading every jar to class file v52 ($JOBS-way parallel; takes a few minutes)"
    # -Djvmdg.javaApi points every process at the extracted patched java-api.jar directly. Without it
    # each process validates a shared .jvmdg/ cache against the api jar embedded in the tool jar and
    # rewrites it on mismatch; the patched tool jar never matches a stale cache, so parallel runs
    # race on that rewrite and fail with truncated or missing cache files.
    export DG_DIR="$DG" JVMDG="$DG/jvmdowngrader-patched.jar"
    ls "$DG/in"/*.jar | xargs -P "$JOBS" -n 1 sh -c '
        n=$(basename "$1")
        java -Xmx2G "-Djvmdg.javaApi=$DG_DIR/nested/META-INF/lib/java-api.jar" -jar "$JVMDG" -c 52 -l WARN downgrade -cp "$(cat "$DG_DIR/cp.txt")" -t "$1" "$DG_DIR/out/$n" > "$DG_DIR/logs/$n.log" 2>&1 || echo "FAILED $n"
    ' downgrade | tee "$DG/failed.txt"
    if grep -q FAILED "$DG/failed.txt"; then echo "some jars failed to downgrade; see work/dg/logs/" >&2; exit 1; fi

    log "post-pass: Fixup (StringBuilder.append(byte/short) does not exist on Java 8)"
    mkdir -p "$DG/fixup"
    "$J8/bin/javac" -cp "$WORK/jvmdowngrader.jar" -d "$DG/fixup" "$ROOT/tools/fixup/Fixup.java" "$ROOT/tools/fixup/CheerpjFixup.java"
    "$J8/bin/java" -cp "$DG/fixup:$WORK/jvmdowngrader.jar" Fixup "$DG/out"/*.jar

    log "post-pass: CheerpjFixup (netty-common MethodHandle uses CheerpJ cannot link)"
    "$J8/bin/java" -cp "$DG/fixup:$WORK/jvmdowngrader.jar" CheerpjFixup "$DG/out"/netty-common-*.jar

    # configurate detects records through name-based reflection (Class.isRecord etc.), which the
    # downgrader cannot rewrite; on Java 8 the lookups fail and record-backed config sections cannot
    # be re-instantiated on a reboot. Swap in a RecordFieldDiscoverer bound to the jvmdg record stubs.
    log "post-pass: configurate record support (tools/configurate/RecordFieldDiscoverer.java)"
    local CONF GEANTY
    CONF="$(ls "$DG/out"/configurate-core-*.jar)"
    GEANTY="$(ls "$DG/out"/geantyref-*.jar)"
    rm -rf "$DG/configurate" && mkdir -p "$DG/configurate"
    "$J8/bin/javac" -nowarn -cp "$DG/api8.jar:$CONF:$GEANTY" -d "$DG/configurate" "$ROOT/tools/configurate/RecordFieldDiscoverer.java"
    (cd "$DG/configurate" && "$J8/bin/jar" uf "$CONF" org)

    touch "$DG/.done"
}

# ---- dist: assemble what the page loads -------------------------------------------------------------

stage_dist() {
    local DG="$WORK/dg" STAGE="$WORK/dist-stage"
    log "assembling the jar set"
    rm -rf "$STAGE" && mkdir -p "$STAGE"
    cp "$DG/api8.jar" "$STAGE/"
    cp "$DG/jvmdowngrader-patched.jar" "$STAGE/jvmdowngrader.jar"
    for j in "$DG/out"/*.jar; do
        case "$(basename "$j")" in
            *jline-terminal-ffm*|*netty-transport-native-epoll*|*netty-transport-native-kqueue*) ;;  # Java 22 FFM / native transports: never loadable in a browser (unix-common stays: the server references its classes)
            *) cp "$j" "$STAGE/" ;;
        esac
    done

    log "compiling the launcher"
    local LCP; LCP="$(ls "$STAGE"/*.jar | tr '\n' ':')"
    rm -rf "$DG/launcher" && mkdir -p "$DG/launcher"
    "$J8/bin/javac" -nowarn -cp "$LCP" -d "$DG/launcher" "$ROOT/launcher/BrowserMain.java"
    (cd "$DG/launcher" && "$J8/bin/jar" cf "$STAGE/launcher.jar" .)

    # The jar directory is named by a hash of its contents. The page requests /jars/<stamp>/…, the
    # server marks /jars/ immutable, and a rebuild changes the stamp: no stale cached jar slices
    # (CheerpJ reads jars with Range requests, and mixing builds corrupts the zip stream).
    local STAMP
    STAMP="$( (cd "$STAGE" && ls | sort | while read -r f; do shasum -a 256 "$f"; done) | shasum -a 256 | cut -c1-12)"
    local DIST="$ROOT/web/public/jars/$STAMP"
    rm -rf "$ROOT/web/public/jars" && mkdir -p "$DIST"
    mv "$STAGE"/*.jar "$DIST/"

    # classpath order matters: stubs first so they win, server jar, libraries, tool runtime, launcher last
    {
        echo "jars/$STAMP/api8.jar"
        echo "jars/$STAMP/paper-$PAPER_VERSION.jar"
        ls "$DIST" | grep -vE "^(api8|paper-$PAPER_VERSION|jvmdowngrader|launcher)\.jar$" | sed "s|^|jars/$STAMP/|"
        echo "jars/$STAMP/jvmdowngrader.jar"
        echo "jars/$STAMP/launcher.jar"
    } > "$ROOT/web/public/classpath.txt"
    echo "   $(ls "$DIST" | wc -l | tr -d ' ') jars in jars/$STAMP, classpath.txt written"
}

# ---- optional: boot the downgraded server on the real JDK 8 -----------------------------------------

verify() {
    log "boot check on JDK 8 (world generates, listener binds; ctrl-c or 'stop' to end)"
    local RUN="$WORK/bootcheck"; mkdir -p "$RUN"
    echo "eula=true" > "$RUN/eula.txt"
    local CP; CP="$(sed "s|^jars/|$ROOT/web/public/jars/|" "$ROOT/web/public/classpath.txt" | grep -v launcher.jar | tr '\n' ':')"
    (cd "$RUN" && "$J8/bin/java" -Xmx3G -cp "$CP" org.bukkit.craftbukkit.Main nogui)
}

case "${1:-all}" in
    all) stage_deps; stage_paper; stage_dg; stage_dist ;;
    deps) stage_deps ;;
    paper) stage_deps; stage_paper ;;
    dg) stage_deps; stage_paper; stage_dg ;;
    dist) stage_deps; stage_paper; stage_dg; stage_dist ;;
    verify) stage_deps; verify ;;
    *) echo "usage: $0 [all|deps|paper|dg|dist|verify]" >&2; exit 1 ;;
esac
log "done"
