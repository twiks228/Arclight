package io.izzel.arclight.common.mixin.core.server;

import io.izzel.arclight.api.Unsafe;
import net.minecraft.server.Bootstrap;
import org.bukkit.craftbukkit.v.util.CraftLegacy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * Mixin for {@link Bootstrap} that patches CraftBukkit's legacy material
 * whitelisting mechanism to accept all block states.
 *
 * <p><b>Context:</b> CraftBukkit's {@link CraftLegacy} class maintains a
 * {@code whitelistedStates} set that controls which block states are considered
 * valid during legacy material conversion. On a modded server, mod-added block
 * states would be rejected because they are not in the whitelist.</p>
 *
 * <p><b>Solution:</b> This mixin replaces the whitelist {@link HashSet} with
 * a custom subclass where {@code contains()} always returns {@code true} (by
 * auto-adding the queried element before checking). This effectively makes the
 * whitelist accept all block states without modifying CraftBukkit's source.</p>
 *
 * <p><b>Trigger condition:</b> Only activates when the caller is
 * {@code CraftLegacy} (detected via stack trace inspection), to avoid
 * interfering with other Bootstrap callers.</p>
 *
 * <p>Yes, this is a dirty hack. But it works reliably and avoids patching
 * CraftBukkit's legacy conversion system, which would be far more fragile.</p>
 */
@Mixin(value = Bootstrap.class, priority = 1100)
public class BootstrapMixin {

    @SuppressWarnings("unchecked")
    @Inject(method = "bootStrap", at = @At("HEAD"))
    private static void arclight$replaceWhitelist(CallbackInfo ci) {
        // Only apply when called from CraftLegacy's static initializer
        if (!new LinkageError().getStackTrace()[2].toString().contains("util.CraftLegacy")) {
            return;
        }

        try {
            Field field = CraftLegacy.class.getDeclaredField("whitelistedStates");
            Object base = Unsafe.staticFieldBase(field);
            long offset = Unsafe.staticFieldOffset(field);
            Set<String> previous = (Set<String>) Unsafe.getObject(base, offset);

            // Replace with a set that auto-adds any queried element,
            // making all contains() calls return true on subsequent checks
            class AutoAcceptSet extends HashSet<String> {
                @Override
                public boolean contains(Object o) {
                    this.add((String) o);
                    return super.contains(o);
                }
            }

            AutoAcceptSet replacement = new AutoAcceptSet();
            replacement.addAll(previous);
            Unsafe.putObject(base, offset, replacement);
        } catch (ReflectiveOperationException e) {
            // Non-critical: legacy material conversion will still work for vanilla states
            e.printStackTrace();
        }
    }
}