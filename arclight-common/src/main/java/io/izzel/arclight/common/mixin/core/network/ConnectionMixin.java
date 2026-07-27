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

@Mixin(Connection.class)
public class ConnectionMixin implements ConnectionBridge {

    @Unique
    private static final Logger ARCLIGHT_J2K$LOGGER = LogManager.getLogger("Arclight-J2K");

    @Shadow 
    public boolean disconnectionHandled;
    
    @Shadow 
    private Channel channel;

    public UUID spoofedUUID;
    public Property[] spoofedProfile;
    public String hostname;

    // ── ConnectionBridge Implementation ──────────────────────────────────────────────

    @Override
    public UUID bridge$getSpoofedUUID() { 
        return spoofedUUID; 
    }

    @Override
    public void bridge$setSpoofedUUID(UUID spoofedUUID) { 
        this.spoofedUUID = spoofedUUID; 
    }

    @Override
    public Property[] bridge$getSpoofedProfile() { 
        return spoofedProfile; 
    }

    @Override
    public void bridge$setSpoofedProfile(Property[] spoofedProfile) { 
        this.spoofedProfile = spoofedProfile; 
    }

    @Override
    public String bridge$getHostname() { 
        return hostname; 
    }

    @Override
    public void bridge$setHostname(String hostname) { 
        this.hostname = hostname; 
    }

    // ── Fixes & Patches ──────────────────────────────────────────────────────────────

    /**
     * Prevents duplicate disconnect handling warnings.
     */
    @Inject(method = "handleDisconnection", at = @At("HEAD"), cancellable = true)
    private void arclight$noDisconnectTwiceWarn(CallbackInfo ci) {
        if (disconnectionHandled) {
            ci.cancel();
        }
    }

    /**
     * ProtocolLib compatibility patch for hybrid servers (NeoForge + Bukkit).
     *
     * When a network exception occurs, Minecraft tries to send a disconnect packet.
     * ProtocolLib intercepts the write via NettyChannelProxy, but the packet codec
     * in Arclight/NeoForge uses different IDs than ProtocolLib expects, causing:
     *   "Sending unknown packet 'clientbound/minecraft:disconnect'"
     *
     * Fix strategy:
     *   1. Detect if our channel is wrapped by ProtocolLib.
     *   2. Try to unwrap and get the real underlying Netty channel.
     *   3. If unwrap succeeds: replace this.channel temporarily so the normal
     *      disconnect flow proceeds through the real channel instead of the proxy.
     *   4. If unwrap fails: log and let the exception bubble up naturally
     *      (do NOT close the channel — let Netty handle it).
     */
    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void arclight$j2k$protocolLibSafeDisconnect(
            ChannelHandlerContext context,
            Throwable throwable,
            CallbackInfo ci
    ) {
        try {
            if (disconnectionHandled) {
                ci.cancel();
                return;
            }

            Channel currentChannel = this.channel;

            if (!arclight$j2k$isProtocolLibProxy(currentChannel)
                    && !arclight$j2k$isProtocolLibProxy(context.channel())) {
                // Not a ProtocolLib channel — let normal handling proceed
                return;
            }

            // Attempt to get the real underlying channel from the proxy
            Channel realChannel = arclight$j2k$unwrapChannel(currentChannel);

            if (realChannel != null && realChannel != currentChannel) {
                // Replace the proxy with the real channel so the disconnect packet
                // goes directly to Netty without passing through ProtocolLib codec
                this.channel = realChannel;

                ARCLIGHT_J2K$LOGGER.debug(
                    "[ProtocolLib-Compat] Unwrapped NettyChannelProxy to real channel {}. " +
                    "Normal disconnect flow will proceed.",
                    realChannel.getClass().getSimpleName()
                );

                // Do NOT cancel — let the original exceptionCaught logic run
                // with the real channel now set
                return;
            }

            // Unwrap failed — just log the issue but do not interfere.
            // Closing the channel here causes reconnect delays.
            // Let Netty and ProtocolLib handle cleanup on their own.
            ARCLIGHT_J2K$LOGGER.debug(
                "[ProtocolLib-Compat] Could not unwrap channel proxy. " +
                "Letting Netty handle cleanup. Cause: {}",
                throwable.getMessage()
            );

        } catch (Throwable t) {
            ARCLIGHT_J2K$LOGGER.warn(
                "[ProtocolLib-Compat] Workaround threw unexpected exception: {}",
                t.toString()
            );
        }
    }

    /**
     * Attempts to extract the real Netty Channel from inside a ProtocolLib proxy.
     * ProtocolLib stores it under various field names depending on version.
     */
    @Unique
    private static Channel arclight$j2k$unwrapChannel(Channel channel) {
        if (channel == null) return null;

        // Field names used by different ProtocolLib versions
        String[] candidates = { "wrappedChannel", "channel", "originalChannel", "delegate" };

        for (String fieldName : candidates) {
            try {
                Field field = arclight$j2k$findField(channel.getClass(), fieldName);
                if (field == null) continue;

                field.setAccessible(true);
                Object value = field.get(channel);

                if (value instanceof Channel real && real != channel) {
                    return real;
                }
            } catch (Throwable ignored) {
                // Ignore reflection errors and try the next candidate
            }
        }

        return null;
    }

    /**
     * Walks the class hierarchy to find a declared field by name.
     */
    @Unique
    private static Field arclight$j2k$findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Returns true if the channel is a ProtocolLib NettyChannelProxy.
     */
    @Unique
    private static boolean arclight$j2k$isProtocolLibProxy(Channel channel) {
        if (channel == null) return false;
        String name = channel.getClass().getName();
        return name.contains("NettyChannelProxy") || name.contains("com.comphenix.protocol");
    }
}