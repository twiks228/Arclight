package io.izzel.arclight.common.mod.server.command;

import io.izzel.arclight.common.mod.server.permission.ArclightDummyPermissible;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.util.CraftChatMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A Bukkit {@link CommandSender} implementation that wraps an NMS
 * {@link CommandSourceStack}, allowing NMS command sources to receive
 * messages through the Bukkit API.
 *
 * <p>Used when Bukkit plugin commands are executed by non-player NMS sources
 * (e.g., command blocks, RCON, functions).</p>
 */
public class ArclightDummyCommandSender extends ArclightDummyPermissible implements CommandSender {

    public final CommandSourceStack stack;

    /** Lazily initialized Spigot API wrapper. */
    private Spigot spigot;

    public ArclightDummyCommandSender(CommandSourceStack stack) {
        this.stack = stack;
    }

    // ── Inner Spigot API ──────────────────────────────────────────────────────

    /**
     * BungeeCord-style rich text message support via the Spigot API.
     *
     * <p>Converts {@link BaseComponent} objects to NMS {@link Component} via JSON string.
     * Uses {@code ComponentSerializer.toString()} (returns String) rather than
     * {@code ComponentSerializer.toJson()} which returns JsonElement in newer versions
     * of the BungeeCord Chat API and is not compatible with
     * {@link Component.Serializer#fromJson(String, net.minecraft.core.HolderLookup.Provider)}.</p>
     */
    public class Spigot extends CommandSender.Spigot {

        /**
         * Sends multiple components as separate messages.
         * Each component is converted and sent individually.
         */
        @Override
        public void sendMessage(@NotNull BaseComponent... components) {
            for (BaseComponent component : components) {
                sendMessage(component);
            }
        }

        @Override
        public void sendMessage(@Nullable UUID sender, @NotNull BaseComponent component) {
            sendMessage(component);
        }

        @Override
        public void sendMessage(@Nullable UUID sender, @NotNull BaseComponent... components) {
            sendMessage(components);
        }

        /**
         * Converts a BungeeCord {@link BaseComponent} to an NMS {@link Component}
         * via JSON string and sends it through the {@link CommandSourceStack}.
         *
         * <p>Conversion path:
         * {@code BaseComponent → JSON String → NMS Component → sendSystemMessage}</p>
         *
         * <p><b>Note:</b> {@code ComponentSerializer.toString(component)} is used
         * (not {@code toJson()}) because the NMS deserializer expects a raw JSON
         * {@link String}, not a {@link com.google.gson.JsonElement}.</p>
         *
         * @param component the component to send
         */
        @Override
        public void sendMessage(@NotNull BaseComponent component) {
            // toString() serializes to a JSON String — compatible with all BungeeCord versions
            String json = ComponentSerializer.toString(component);
            Component result = Component.Serializer.fromJson(
                json,
                stack.getServer().registryAccess()
            );
            if (result != null) {
                stack.sendSystemMessage(result);
            }
        }
    }

    // ── CommandSender plain text messages ─────────────────────────────────────

    @Override
    public void sendMessage(@NotNull String s) {
        for (Component msg : CraftChatMessage.fromString(s)) {
            stack.sendSystemMessage(msg);
        }
    }

    @Override
    public void sendMessage(@NotNull String... strings) {
        for (String raw : strings) {
            sendMessage(raw);
        }
    }

    @Override
    public void sendMessage(@Nullable UUID uuid, @NotNull String s) {
        sendMessage(s);
    }

    @Override
    public void sendMessage(@Nullable UUID uuid, @NotNull String... strings) {
        sendMessage(strings);
    }

    // ── CommandSender metadata ────────────────────────────────────────────────

    @Override
    public @NotNull Server getServer() {
        // TODO: Replace with stack.getServer().bridge$getServer() after PR #1724 is merged
        return Bukkit.getServer();
    }

    @Override
    public @NotNull String getName() {
        return stack.getTextName();
    }

    @Override
    public @NotNull Spigot spigot() {
        if (spigot == null) {
            spigot = new Spigot();
        }
        return spigot;
    }
}