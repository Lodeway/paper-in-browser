import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

// Launcher for the CheerpJ page. Routes stdout/stderr through a JS-implemented native so output from ALL
// threads reaches the page, bridges tunneled Minecraft connections into the server's in-JVM listener, and
// services the page's file-manager/console requests over a pull-based ops channel (CheerpJ forbids JS->Java
// calls while the main JVM runs, so the page can only answer natives — Java threads poll for work).
public class BrowserMain {
    private static native void consoleLine(String line);

    private static java.io.Writer logFile;

    static volatile boolean startupDone;
    /** True once MinecraftServer's class initialization is safely over (the server logged from initServer).
     *  Background threads must not touch the class before then: Class.forName from a background thread would
     *  run MinecraftServer's huge static-init graph concurrently with main's Bootstrap.bootStrap(), which
     *  deadlocks CheerpJ's cooperative threads on the class-init locks. */
    static volatile boolean serverClassReady;

    static synchronized void emit(String line) {
        if (line.contains("Done (") && line.contains("For help, type")) startupDone = true;
        if (line.contains("Starting minecraft server")) serverClassReady = true;
        try {
            consoleLine(line);
        } catch (Throwable ignored) {
        }
        try {
            if (logFile == null) {
                logFile = new java.io.OutputStreamWriter(new java.io.FileOutputStream("/files/console.log", true), "UTF-8");
            }
            logFile.write(line);
            logFile.write('\n');
            logFile.flush();
        } catch (Throwable ignored) {
        }
    }

    static final class LineStream extends OutputStream {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit(buffer.toString());
                buffer.setLength(0);
            } else if (b != '\r') {
                buffer.append((char) (b & 0xFF));
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new File("/files/console.log").delete();
        PrintStream bridge = new PrintStream(new LineStream(), true, "UTF-8");
        System.setOut(bridge);
        System.setErr(bridge);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { bridge.println("[labs] uncaught in thread " + t.getName() + ":"); e.printStackTrace(bridge); });
        List<String> passthrough = new ArrayList<>();
        boolean eulaAccepted = false, freshWorld = false;
        for (String a : args) { if (a.equals("--labs-eula-accepted")) eulaAccepted = true; else if (a.equals("--labs-fresh-world")) freshWorld = true; else passthrough.add(a); }
        File cwd = new File(System.getProperty("user.dir"));
        if (freshWorld) {
            for (String name : new String[] {"world", "world_nether", "world_the_end", "crash-reports"}) {
                File dir = new File(cwd, name);
                if (dir.exists()) { int n = deleteRecursively(dir); bridge.println("[labs] removed previous " + name + " (" + n + " entries)"); }
            }
            if (new File(cwd, "server.properties").delete()) bridge.println("[labs] removed previous server.properties");
        }
        seedWorld(cwd, bridge);
        // Paper's configurate-based configs cannot be read back on this JVM: their record-backed
        // sections deserialize through a zero-argument constructor that downgraded records do not
        // have, so a second boot dies in initializeGlobalConfiguration. Until the stubs learn
        // records, regenerate those files from defaults every boot (server.properties, bukkit.yml,
        // spigot.yml and the world all persist normally).
        for (String stale : new String[] {"config/paper-global.yml", "config/paper-world-defaults.yml", "world/paper-world.yml", "world_nether/paper-world.yml", "world_the_end/paper-world.yml"}) {
            new File(cwd, stale).delete();
        }
        // spark's background profiler samples ThreadMXBean, which this JVM does not implement; keep spark (commands,
        // tick reporting) but do not start the background sampler.
        File sparkDir = new File(new File(cwd, "plugins"), "spark");
        File sparkConfig = new File(sparkDir, "config.json");
        if (!sparkConfig.exists()) {
            sparkDir.mkdirs();
            try (Writer w = new FileWriter(sparkConfig)) {
                w.write("{\"backgroundProfiler\": false}");
            }
        }
        if (eulaAccepted) {
            File eula = new File(cwd, "eula.txt");
            try (Writer w = new FileWriter(eula)) {
                w.write("# Recorded by the page after the user agreed to the Minecraft EULA (https://aka.ms/MinecraftEULA)\n");
                w.write("eula=true\n");
            }
        }
        startTunnelReceiver();
        startOpsReceiver();
        String[] rest = passthrough.toArray(new String[0]);
        bridge.println("[labs] launching org.bukkit.craftbukkit.Main " + String.join(" ", rest));
        try {
            org.bukkit.craftbukkit.Main.main(rest);
            bridge.println("[labs] Main.main returned; server thread running");
        } catch (Throwable t) {
            bridge.println("[labs] Main.main threw:");
            t.printStackTrace(bridge);
        }
    }

    /** First boot ships a pre-generated world: /app/world.zip (served next to the jars) unpacked into the
     *  persistent filesystem, so "Preparing spawn area" is a copy instead of terrain generation. */
    private static void seedWorld(File cwd, PrintStream log) {
        if (new File(cwd, "world").exists()) return;
        File zip = new File("/app/world.zip");
        if (!zip.exists()) return;
        long t0 = System.currentTimeMillis();
        int files = 0;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zip), 1 << 16))) {
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[1 << 16];
            while ((entry = in.getNextEntry()) != null) {
                File out = new File(cwd, entry.getName());
                // classic zip-slip guard: refuse entries that escape the working directory
                if (!out.getCanonicalPath().startsWith(cwd.getCanonicalPath() + File.separator)) continue;
                if (entry.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream o = new java.io.FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                files++;
            }
            log.println("[labs] unpacked pre-warmed world: " + files + " files in " + (System.currentTimeMillis() - t0) / 1000 + "s");
        } catch (Throwable t) {
            log.println("[labs] world.zip unpack failed (starting with a fresh world): " + t);
        }
    }

    // ---- Ops channel ----------------------------------------------------------------------------------------
    // The page's file manager and console input arrive here. Request frame (from opsPoll):
    //   [id u32 BE][op u8][pathLen u16 BE][path utf8][payload]
    // ops: 1 LIST (reply = JSON array of {name,size,dir,mtime}), 2 READ (reply = file bytes),
    //      3 WRITE (payload = contents), 4 DELETE (recursive), 5 MKDIR,
    //      6 COMMAND (payload = console command line), 7 STOP (queues the "stop" command).
    // Replies go back through opsReply(id, ok, payload) — errors carry the message as UTF-8 payload with ok=false.
    private static native byte[] opsPoll();
    private static native void opsReply(int id, boolean ok, byte[] payload);

    static void startOpsReceiver() {
        Thread rx = new Thread(() -> {
            while (true) {
                byte[] frame;
                try {
                    frame = opsPoll();
                } catch (Throwable t) {
                    emit("[labs] ops receiver stopped: " + t);
                    return;
                }
                if (frame == null || frame.length < 7) continue;
                int id = ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16) | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
                int op = frame[4] & 0xFF;
                int pathLen = ((frame[5] & 0xFF) << 8) | (frame[6] & 0xFF);
                try {
                    String path = new String(frame, 7, pathLen, java.nio.charset.StandardCharsets.UTF_8);
                    byte[] payload = java.util.Arrays.copyOfRange(frame, 7 + pathLen, frame.length);
                    opsReply(id, true, handleOp(op, path, payload));
                } catch (Throwable t) {
                    String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                    try { opsReply(id, false, msg.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Throwable ignored) {}
                }
            }
        }, "labs-ops");
        rx.setDaemon(true);
        rx.start();
    }

    private static byte[] handleOp(int op, String path, byte[] payload) throws Exception {
        File root = new File(System.getProperty("user.dir"));
        switch (op) {
            case 1: {
                File dir = resolve(root, path);
                File[] children = dir.listFiles();
                if (children == null) throw new java.io.FileNotFoundException(path + " is not a directory");
                StringBuilder json = new StringBuilder("[");
                for (File c : children) {
                    if (json.length() > 1) json.append(',');
                    json.append("{\"name\":\"").append(jsonEscape(c.getName()))
                        .append("\",\"size\":").append(fileSize(c))
                        .append(",\"dir\":").append(c.isDirectory())
                        .append(",\"mtime\":").append(c.lastModified()).append('}');
                }
                return json.append(']').toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            case 2: {
                return readFully(resolve(root, path));
            }
            case 3: {
                File f = resolve(root, path);
                f.getParentFile().mkdirs();
                try (OutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(payload);
                }
                return EMPTY;
            }
            case 4: {
                deleteRecursively(resolve(root, path));
                return EMPTY;
            }
            case 5: {
                resolve(root, path).mkdirs();
                return EMPTY;
            }
            case 6: {
                queueConsoleCommand(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                return EMPTY;
            }
            case 7: {
                queueConsoleCommand("stop");
                return EMPTY;
            }
            default:
                throw new IllegalArgumentException("unknown op " + op);
        }
    }

    private static final byte[] EMPTY = new byte[0];

    /** File.length() reports 0 on CheerpJ's filesystem for files the JVM wrote this session, so
     *  sizing a buffer from it truncates reads to nothing. Stream until EOF instead. */
    private static byte[] readFully(File f) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(1024, (int) f.length()));
        byte[] buf = new byte[1 << 16];
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Same File.length() problem, for the listing's size column: fall back to the stream's
     *  available() (exact for CheerpJ's in-memory files) when length() claims 0. */
    private static long fileSize(File f) {
        long len = f.length();
        if (len > 0 || f.isDirectory()) return len;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            return in.available();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private static File resolve(File root, String path) throws java.io.IOException {
        File f = new File(root, path);
        // keep the file manager inside the server directory — same rule as the zip unpack
        if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) throw new java.io.IOException("path escapes the server directory");
        return f;
    }

    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    // Console input the way the real console does it: DedicatedServer keeps a queue of pending console
    // commands drained on the server thread each tick, so handing it a line is thread-safe. Resolved by
    // reflection with a method-scan fallback so a rename in a future Paper version degrades to a log line
    // instead of a launcher that will not compile.
    private static java.lang.reflect.Method consoleInput;
    private static Object dedicatedServer, commandSource;

    static synchronized void queueConsoleCommand(String command) throws Exception {
        if (!startupDone) throw new IllegalStateException("server is still starting");
        if (consoleInput == null) {
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            dedicatedServer = serverClass.getMethod("getServer").invoke(null);
            Class<?> sourceClass = Class.forName("net.minecraft.commands.CommandSourceStack");
            java.lang.reflect.Method createSource = null;
            for (java.lang.reflect.Method m : serverClass.getMethods()) {
                if (m.getReturnType() == sourceClass && m.getParameterCount() == 0) { createSource = m; break; }
            }
            if (createSource == null) throw new NoSuchMethodException("no CommandSourceStack factory on MinecraftServer");
            commandSource = createSource.invoke(dedicatedServer);
            for (java.lang.reflect.Method m : dedicatedServer.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == String.class && p[1] == sourceClass && m.getReturnType() == void.class) { consoleInput = m; break; }
            }
            if (consoleInput == null) throw new NoSuchMethodException("no console-input method on " + dedicatedServer.getClass().getName());
        }
        consoleInput.invoke(dedicatedServer, command, commandSource);
    }

    // ---- WebSocket tunnel bridge ----------------------------------------------------------------------------
    // The page receives multiplexed client connections from the tunnel server and hands them to these methods
    // (pull-based, same reason as the ops channel). Each remote connection becomes a netty LocalChannel into the
    // server's local listener (io.papermc.paper.network.LocalTransport), carrying ordinary protocol bytes.
    private static native void tunnelSend(int id, byte[] data);
    private static native void tunnelClosed(int id);
    /** Blocks (asynchronously, page-side) until the page has a tunnel frame: [type u8][id u32 BE][payload]. */
    private static native byte[] tunnelPoll();

    static void startTunnelReceiver() {
        Thread rx = new Thread(() -> {
            while (true) {
                byte[] frame;
                try {
                    frame = tunnelPoll();
                } catch (Throwable t) {
                    emit("[labs] tunnel receiver stopped: " + t);
                    return;
                }
                if (frame == null || frame.length < 5) continue;
                int id = ((frame[1] & 0xFF) << 24) | ((frame[2] & 0xFF) << 16) | ((frame[3] & 0xFF) << 8) | (frame[4] & 0xFF);
                byte[] payload = java.util.Arrays.copyOfRange(frame, 5, frame.length);
                switch (frame[0]) {
                    case 1: tunnelOpen(id, new String(payload, java.nio.charset.StandardCharsets.UTF_8)); break;
                    case 2: tunnelData(id, payload); break;
                    case 3: tunnelClose(id); break;
                    default: break;
                }
            }
        }, "labs-tunnel-rx");
        rx.setDaemon(true);
        rx.start();
    }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, io.netty.channel.Channel> TUNNELS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.List<byte[]>> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static io.netty.channel.EventLoopGroup tunnelGroup;

    private static synchronized io.netty.channel.EventLoopGroup tunnelGroup() {
        if (tunnelGroup == null) {
            tunnelGroup = new io.netty.channel.DefaultEventLoopGroup(1, r -> { Thread t = new Thread(r, "labs-tunnel-bridge"); t.setDaemon(true); return t; });
        }
        return tunnelGroup;
    }

    public static void tunnelOpen(final int id, final String remote) {
        final io.netty.channel.local.LocalAddress target = io.papermc.paper.network.LocalTransport.address();
        if (target == null) {
            emit("[labs] connection " + id + " from " + remote + " refused: server not listening yet");
            tunnelClosed(id);
            return;
        }
        PENDING.put(id, java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
        new io.netty.bootstrap.Bootstrap()
            .group(tunnelGroup())
            .channel(io.netty.channel.local.LocalChannel.class)
            .handler(new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
                @Override
                protected void initChannel(io.netty.channel.Channel ch) {
                    ch.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                            io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) msg;
                            byte[] data = new byte[buf.readableBytes()];
                            buf.readBytes(data);
                            buf.release();
                            tunnelSend(id, data);
                        }

                        @Override
                        public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) {
                            if (TUNNELS.remove(id) != null) tunnelClosed(id);
                        }

                        @Override
                        public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                            emit("[labs] connection " + id + ": " + cause);
                            ctx.close();
                        }
                    });
                }
            })
            .connect(target)
            .addListener((io.netty.channel.ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    emit("[labs] connection " + id + " could not reach the server listener: " + future.cause());
                    PENDING.remove(id);
                    tunnelClosed(id);
                    return;
                }
                io.netty.channel.Channel ch = future.channel();
                // The local listener expects a PROXY protocol header so the server sees the real client address.
                ch.write(io.netty.buffer.Unpooled.copiedBuffer(proxyHeader(remote), java.nio.charset.StandardCharsets.US_ASCII));
                TUNNELS.put(id, ch);
                java.util.List<byte[]> queued = PENDING.remove(id);
                if (queued != null) {
                    synchronized (queued) {
                        for (byte[] chunk : queued) ch.write(io.netty.buffer.Unpooled.wrappedBuffer(chunk));
                    }
                    ch.flush();
                }
            });
    }

    /** PROXY protocol v1 line for a "host:port" remote address (IPv6 as "[addr]:port"). */
    static String proxyHeader(String remote) {
        String host = remote, port = "0";
        int colon = remote.lastIndexOf(':');
        if (colon > 0) { host = remote.substring(0, colon); port = remote.substring(colon + 1); }
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        boolean v6 = host.indexOf(':') >= 0;
        return "PROXY " + (v6 ? "TCP6 " : "TCP4 ") + host + " " + (v6 ? "::" : "0.0.0.0") + " " + port + " 25565\r\n";
    }

    public static void tunnelData(int id, byte[] data) {
        io.netty.channel.Channel ch = TUNNELS.get(id);
        if (ch != null) {
            ch.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(data));
            return;
        }
        java.util.List<byte[]> queued = PENDING.get(id);
        if (queued != null) queued.add(data);
    }

    public static void tunnelClose(int id) {
        PENDING.remove(id);
        io.netty.channel.Channel ch = TUNNELS.remove(id);
        if (ch != null) ch.close();
    }

    private static int deleteRecursively(File f) {
        int n = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) n += deleteRecursively(c);
        return n + (f.delete() ? 1 : 0);
    }
}
