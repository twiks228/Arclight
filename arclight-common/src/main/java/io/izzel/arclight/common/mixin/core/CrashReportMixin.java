package io.izzel.arclight.common.mixin.core;

import io.izzel.arclight.api.ArclightVersion;
import io.izzel.arclight.common.mod.server.ArclightServer;
import net.minecraft.CrashReport;
import net.minecraft.SystemReport;
import org.bukkit.craftbukkit.v.CraftCrashReport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link CrashReport} that appends Arclight-specific information
 * to the system report section of every crash report.
 *
 * <p>Adds two entries:</p>
 * <ul>
 *   <li>{@code Arclight Release} — the current Arclight release name
 *       (e.g., {@code "J2K-1.0.2-SNAPSHOT"}). Uses a lazy supplier so the
 *       release name is not evaluated until the report is formatted.</li>
 *   <li>{@code Arclight} — Bukkit server diagnostics from {@link CraftCrashReport}
 *       if the server has been initialized, or a descriptive message if the crash
 *       occurred before the Bukkit layer was set up.</li>
 * </ul>
 *
 * <p>This helps server administrators identify Arclight-specific configuration
 * and plugin state when reporting crashes.</p>
 */
@Mixin(value = CrashReport.class, priority = 1100)
public class CrashReportMixin {

    @Shadow @Final private SystemReport systemReport;

    /**
     * Appends Arclight version and Bukkit diagnostic info to every crash report.
     * Runs after the {@link CrashReport} constructor to ensure the system report
     * is already initialized.
     *
     * @param message   the crash message (unused here)
     * @param throwable the throwable that caused the crash (unused here)
     * @param ci        injection callback info
     */
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void arclight$additional(String message, Throwable throwable, CallbackInfo ci) {
        // Use a lazy supplier so the version string is only computed when the
        // crash report is actually formatted (avoids potential NPE during init)
        this.systemReport.setDetail(
            "Arclight Release",
            ArclightVersion.current()::getReleaseName
        );

        if (ArclightServer.isInitialized()) {
            // Server is up — include full Bukkit diagnostics (plugins, worlds, etc.)
            this.systemReport.setDetail("Arclight", new CraftCrashReport());
        } else {
            // Crash happened before the Bukkit server layer was initialized
            this.systemReport.setDetail(
                "Arclight",
                "The crash occurred before server initialization."
            );
        }
    }
}