package io.izzel.arclight.common.mixin.core.network;

import com.mojang.authlib.properties.Property;
import io.izzel.arclight.common.bridge.core.network.ConnectionBridge;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Mixin for {@link Connection} that adds BungeeCord/Velocity IP forwarding support
 * and a compatibility fix for ProtocolLib on hybrid NeoForge+Bukkit servers.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Stores spoofed UUID and profile properties from BungeeCord/Velocity</li>
 *   <li>Stores raw hostname for Bukkit IP forwarding handshake parsing</li>
 *   <li>Prevents double-disconnect log spam</li>
 *   <li>Fixes "Sending unknown packet 'clientbound/minecraft:disconnect'" when
 *       ProtocolLib is installed alongside NeoForge mods</li>
 * </ul>
 */
@Mixin(value = Connection.class, priority = 900)
public class ConnectionMixin implements ConnectionBridge {

    @Unique
    private static final Logger ARCLIGHT_J2K$LOGGER =
        LogManager.getLogger("Arclight-J2K");

    // ── Shadowed fields ──────────────────────────────────────────────────────

    @Shadow public boolean disconnectionHandled;
    @Shadow private Channel channel;

    // ── BungeeCord / Velocity forwarding fields ──────────────────────────────

    /** Spoofed player UUID from BungeeCord/Velocity IP forwarding. */
    public UUID spoofedUUID;

    /** Spoofed profile properties (skin, etc.) from BungeeCord/Velocity. */
    public Property[] spoofedProfile;

    /** Raw hostname string from the handshake, including BungeeCord extra data. */
    public String hostname;

    // ── ConnectionBridge implementation ──────────────────────────────────────

    @Override public UUID bridge$getSpoofedUUID() { return spoofedUUID; }
    @Override public void bridge$setSpoofedUUID(UUID uuid) { this.spoofedUUID = uuid; }

    @Override public Property[] bridge$getSpoofedProfile() { return spoofedProfile; }
    @Override public void bridge$setSpoofedProfile(Property[] p) { this.spoofedProfile = p; }

    @Override public String bridge$getHostname() { return hostname; }
    @Override public void bridge$setHostname(String h) { this.hostname = h; }

    // ── Disconnect duplicate fix ─────────────────────────────────────────────

    /**
     * Prevents {@code handleDisconnection()} from running more than once,
     * which would cause duplicate "Player disconnected" log entries.
     */
    @Inject(
        method = "handleDisconnection",
        at = @At("HEAD"),
        cancellable = true
    )
    private void arclight$preventDuplicateDisconnect(CallbackInfo ci) {
        if (disconnectionHandled) {
            ci.cancel();
        }
    }

    // ── ProtocolLib compatibility ─────────────────────────────────────────────

    /**
     * Fixes "Sending unknown packet 'clientbound/minecraft:disconnect'" when
     * ProtocolLib is present on a hybrid NeoForge+Bukkit server.
     *
     * <p><b>Problem:</b> When a network exception occurs while ProtocolLib is active,
     * Minecraft's {@code exceptionCaught} handler tries to send a disconnect packet.
     * ProtocolLib intercepts this via {@code NettyChannelProxy}, but the packet codec
     * registered by Arclight/NeoForge uses different packet IDs than ProtocolLib's
     * injected codec expects. This produces:</p>
     * <pre>
     *   [ERROR] Sending unknown packet 'clientbound/minecraft:disconnect'
     * </pre>
     *
     * <p><b>Fix:</b></p>
     * <ol>
     *   <li>Detect if our channel is wrapped by a ProtocolLib proxy.</li>
     *   <li>Extract the real underlying Netty channel via reflection.</li>
     *   <li>If extraction succeeds: swap {@code this.channel} to bypass the proxy
     *       codec and let the disconnect packet go directly to Netty.</li>
     *   <li>If extraction fails: log at DEBUG and do nothing — let Netty's pipeline
     *       handle cleanup. Do NOT close the channel manually as this causes
     *       unnecessary reconnect delays.</li>
     * </ol>
     *
     * <p>Priority 900 ensures this runs before ProtocolLib's own handlers.</p>
     */
    @Inject(
        method = "exceptionCaught",
        at = @At("HEAD"),
        cancellable = true
    )
    private void arclight$protocolLibSafeDisconnect(
            ChannelHandlerContext ctx,
            Throwable cause,
            CallbackInfo ci
    ) {
        try {
            // Already disconnected — nothing more to do
            if (disconnectionHandled) {
                ci.cancel();
                return;
            }

            // Only intercept if a ProtocolLib proxy is in the pipeline
            boolean isProxy = arclight$isProtocolLibProxy(this.channel)
                || arclight$isProtocolLibProxy(ctx.channel());

            if (!isProxy) {
                // Standard Netty pipeline — let vanilla handling proceed normally
                return;
            }

            // Attempt to unwrap the real channel from the proxy
            Channel real = arclight$unwrapChannel(this.channel);

            if (real != null && real != this.channel) {
                // Swap to the real channel so the disconnect packet bypasses
                // ProtocolLib's codec and goes directly to Netty
                this.channel = real;

                ARCLIGHT_J2K$LOGGER.debug(
                    "[ProtocolLib-Compat] Unwrapped {} → {}. Proceeding with disconnect.",
                    this.channel.getClass().getSimpleName(),
                    real.getClass().getSimpleName()
                );

                // Do NOT cancel — let vanilla exceptionCaught continue with real channel
                return;
            }

            // Could not unwrap — stand back and let Netty/ProtocolLib handle it
            ARCLIGHT_J2K$LOGGER.debug(
                "[ProtocolLib-Compat] Could not unwrap proxy channel. " +
                "Allowing Netty to handle cleanup. Cause: {}",
                cause.getMessage()
            );

        } catch (Throwable t) {
            // Never let our fix crash the server tick thread
            ARCLIGHT_J2K$LOGGER.warn(
                "[ProtocolLib-Compat] Unexpected error in disconnect workaround: {}",
                t.toString()
            );
        }
    }

    // ── Utility helpers ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given channel is a ProtocolLib
     * {@code NettyChannelProxy} or any class in the ProtocolLib namespace.
     *
     * @param ch the channel to inspect; may be {@code null}
     */
    @Unique
    private static boolean arclight$isProtocolLibProxy(Channel ch) {
        if (ch == null) return false;
        String name = ch.getClass().getName();
        return name.contains("NettyChannelProxy")
            || name.contains("com.comphenix.protocol");
    }

    /**
     * Attempts to extract the real underlying {@link Channel} from a ProtocolLib
     * proxy using reflection over common field name candidates.
     *
     * <p>ProtocolLib has changed its internal field name across versions, so we
     * try multiple candidates in order of most-to-least common.</p>
     *
     * @param ch the (potentially wrapped) channel; may be {@code null}
     * @return the unwrapped channel, or {@code null} if extraction failed
     */
    @Unique
    private static Channel arclight$unwrapChannel(Channel ch) {
        if (ch == null) return null;

        // Field name candidates used across different ProtocolLib versions
        for (String candidate : new String[]{ "wrappedChannel", "channel", "originalChannel", "delegate" }) {
            try {
                Field f = arclight$findField(ch.getClass(), candidate);
                if (f == null) continue;
                f.setAccessible(true);
                Object val = f.get(ch);
                if (val instanceof Channel real && real != ch) {
                    return real;
                }
            } catch (Throwable ignored) {
                // Try next candidate
            }
        }
        return null;
    }

    /**
     * Searches the class hierarchy (including superclasses) for a declared field
     * with the given name.
     *
     * @param clazz the class to start from
     * @param name  the field name to find
     * @return the {@link Field}, or {@code null} if not found in any superclass
     */
    @Unique
    private static Field arclight$findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Walk up
            }
        }
        return null;
    }
}