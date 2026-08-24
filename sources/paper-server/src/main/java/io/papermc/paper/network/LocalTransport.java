package io.papermc.paper.network;

import io.netty.channel.Channel;
import io.netty.channel.local.LocalAddress;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * In-JVM transport ({@code -Dpaper.network.transport=local}): the server listens on a netty {@link LocalAddress}
 * with the normal byte-serialization pipeline, so in-process bridges (e.g. a WebSocket tunnel) can connect
 * {@code LocalChannel}s carrying ordinary Minecraft protocol bytes. Bridges must start each connection with a
 * PROXY protocol (v1 or v2) header carrying the real client address; the listener always runs the HAProxy decoder.
 */
@NullMarked
public final class LocalTransport {
    public static final String PROPERTY = "paper.network.transport";
    private static volatile @Nullable LocalAddress address;

    private LocalTransport() {
    }

    public static boolean enabled() {
        return "local".equalsIgnoreCase(System.getProperty(PROPERTY));
    }

    public static LocalAddress listenAddress() {
        return new LocalAddress("paper-server");
    }

    public static void bound(final LocalAddress boundAddress) {
        address = boundAddress;
    }

    /**
     * The transport-level ("raw") remote address of a channel, independent of any PROXY header. A {@code LocalChannel}
     * has no socket: its peer is a bridge in this process, so the loopback address is the honest answer.
     */
    public static InetAddress rawAddress(final Channel channel) {
        return channel.remoteAddress() instanceof final InetSocketAddress inet ? inet.getAddress() : InetAddress.getLoopbackAddress();
    }

    /** The address the server listens on, or null when the local transport is not in use / not yet bound. */
    public static @Nullable LocalAddress address() {
        return address;
    }
}
