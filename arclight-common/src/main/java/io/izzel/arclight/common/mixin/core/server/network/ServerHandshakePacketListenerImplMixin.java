package io.izzel.arclight.common.mixin.core.server.network;

import com.google.gson.Gson;
import com.mojang.authlib.properties.Property;
import com.mojang.util.UndashedUuid;
import io.izzel.arclight.common.bridge.core.network.ConnectionBridge;
import io.izzel.arclight.common.mod.util.VelocitySupport;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.apache.logging.log4j.LogManager;
import org.bukkit.Bukkit;
import org.spigotmc.SpigotConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.regex.Pattern;

/**
 * Mixin for {@link ServerHandshakePacketListenerImpl} that adds:
 * <ul>
 *   <li>BungeeCord IP forwarding support (hostname-based UUID/profile spoofing)</li>
 *   <li>Connection throttling to prevent login spam attacks</li>
 *   <li>Hostname storage for Bukkit's {@code Server#getIp()} and related APIs</li>
 * </ul>
 *
 * <p>Injection points are chosen to run <em>after</em> Forge's own handshake
 * checks to avoid interfering with mod channel negotiation.</p>
 */
@Mixin(value = ServerHandshakePacketListenerImpl.class, priority = 1100)
public abstract class ServerHandshakePacketListenerImplMixin {

    // ── Constants ─────────────────────────────────────────────────────────────

    @Unique
    private static final Gson GSON = new Gson();

    /**
     * Regex for validating IP addresses in BungeeCord forwarding data.
     * Accepts IPv4 dotted-decimal and IPv6 hex-colon notation.
     */
    @Unique
    private static final Pattern HOST_PATTERN = Pattern.compile("[0-9a-f\\.:]{0,45}");

    /**
     * Connection throttle tracking map.
     * Maps client IP addresses to the timestamp of their last connection attempt.
     * Cleaned periodically when {@link #arclight$throttleCounter} exceeds 200.
     */
    @Unique
    private static final HashMap<InetAddress, Long> THROTTLE_TRACKER = new HashMap<>();

    /** Counter used to periodically trigger throttle map cleanup. */
    @Unique
    private static int arclight$throttleCounter = 0;

    // ── Shadows ───────────────────────────────────────────────────────────────

    @Shadow @Final private Connection connection;

    // ── Hostname capture ──────────────────────────────────────────────────────

    /**
     * Stores the raw hostname from the handshake packet on the connection bridge.
     * This is used by Bukkit to report the server address the player connected to.
     *
     * <p>Injected at the INVOKE of {@code intention()} rather than HEAD to ensure
     * this runs after Forge's own handshake validation.</p>
     *
     * @param packet the handshake packet
     * @param ci     callback info
     */
    @Inject(
        method = "handleIntention",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/handshake/ClientIntentionPacket;intention()Lnet/minecraft/network/protocol/handshake/ClientIntent;"
        )
    )
    private void arclight$setHostName(ClientIntentionPacket packet, CallbackInfo ci) {
        ((ConnectionBridge) this.connection).bridge$setHostname(
            packet.hostName() + ":" + packet.port()
        );
    }

    // ── Connection throttling ─────────────────────────────────────────────────

    /**
     * Implements Bukkit's connection throttle to prevent login spam.
     *
     * <p>If a client reconnects faster than the configured
     * {@code connection-throttle} interval (from {@code bukkit.yml}),
     * the connection is rejected with a friendly message.</p>
     *
     * <p>Localhost (127.0.0.1) is exempt from throttling to allow
     * local development and testing.</p>
     *
     * @param packet the handshake packet
     * @param bl     whether this is a transfer connection
     * @param ci     callback info for cancellation
     */
    @Inject(
        method = "beginLogin",
        cancellable = true,
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/Connection;setupOutboundProtocol(Lnet/minecraft/network/ProtocolInfo;)V"
        )
    )
    private void arclight$throttler(ClientIntentionPacket packet, boolean bl, CallbackInfo ci) {
        try {
            long currentTime = System.currentTimeMillis();
            long connectionThrottle = Bukkit.getServer().getConnectionThrottle();
            InetAddress address = ((InetSocketAddress) this.connection.getRemoteAddress()).getAddress();

            synchronized (THROTTLE_TRACKER) {
                if (THROTTLE_TRACKER.containsKey(address)
                        && !"127.0.0.1".equals(address.getHostAddress())
                        && currentTime - THROTTLE_TRACKER.get(address) < connectionThrottle) {
                    THROTTLE_TRACKER.put(address, currentTime);
                    Component message = Component.literal(
                        "Connection throttled! Please wait before reconnecting."
                    );
                    this.connection.send(new ClientboundLoginDisconnectPacket(message));
                    this.connection.disconnect(message);
                    ci.cancel();
                    return;
                }

                THROTTLE_TRACKER.put(address, currentTime);
                ++arclight$throttleCounter;

                // Periodically clean up stale entries to prevent memory leaks
                if (arclight$throttleCounter > 200) {
                    arclight$throttleCounter = 0;
                    THROTTLE_TRACKER.entrySet().removeIf(
                        entry -> currentTime - entry.getValue() > connectionThrottle
                    );
                }
            }
        } catch (Throwable t) {
            LogManager.getLogger().debug("Failed to check connection throttle", t);
        }
    }

    // ── BungeeCord / Velocity proxy support ───────────────────────────────────

    /**
     * Parses BungeeCord IP forwarding data from the handshake hostname field.
     *
     * <p>BungeeCord appends forwarding data to the hostname using null-byte
     * separators in the format: {@code hostname\0ip\0uuid[\0properties_json]}</p>
     *
     * <p>If Velocity modern forwarding is enabled, this handler is skipped
     * entirely (Velocity uses plugin channels instead of hostname spoofing).</p>
     *
     * <p>If BungeeCord mode is disabled but forwarding data is detected,
     * the player is disconnected with a helpful error message suggesting
     * they enable BungeeCord in {@code spigot.yml}.</p>
     *
     * @param packet the handshake packet containing the hostname
     * @param bl     whether this is a transfer connection
     * @param ci     callback info for cancellation
     */
    @Inject(
        method = "beginLogin",
        cancellable = true,
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/Connection;setupInboundProtocol(Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/PacketListener;)V"
        )
    )
    private void arclight$proxySupport(ClientIntentionPacket packet, boolean bl, CallbackInfo ci) {
        // Velocity uses its own forwarding mechanism — skip hostname parsing
        if (VelocitySupport.isEnabled()) return;

        String[] split = packet.hostName().split("\00");

        if (SpigotConfig.bungee) {
            // BungeeCord mode enabled — parse forwarding data
            if ((split.length == 3 || split.length == 4)
                    && HOST_PATTERN.matcher(split[1]).matches()) {
                // Valid BungeeCord forwarding data
                ((ConnectionBridge) this.connection).bridge$setHostname(split[0]);
                this.connection.address = new InetSocketAddress(
                    split[1],
                    ((InetSocketAddress) this.connection.getRemoteAddress()).getPort()
                );
                ((ConnectionBridge) this.connection).bridge$setSpoofedUUID(
                    UndashedUuid.fromStringLenient(split[2])
                );

                // Optional: profile properties (skin data, etc.)
                if (split.length == 4) {
                    ((ConnectionBridge) this.connection).bridge$setSpoofedProfile(
                        GSON.fromJson(split[3], Property[].class)
                    );
                }
            } else {
                // BungeeCord mode is enabled but the forwarding data is missing/invalid
                Component message = Component.literal(
                    "If you wish to use IP forwarding, please enable it in your BungeeCord config as well!"
                );
                this.connection.send(new ClientboundLoginDisconnectPacket(message));
                this.connection.disconnect(message);
                ci.cancel();
            }
        } else if ((split.length == 3 || split.length == 4)
                && HOST_PATTERN.matcher(split[1]).matches()) {
            // BungeeCord mode is disabled but forwarding data was detected —
            // the player is likely connecting through a proxy without proper config
            Component message = Component.literal(
                "Unknown data in login hostname, did you forget to enable BungeeCord in spigot.yml?"
            );
            this.connection.send(new ClientboundLoginDisconnectPacket(message));
            this.connection.disconnect(message);
            ci.cancel();
        }
    }
}