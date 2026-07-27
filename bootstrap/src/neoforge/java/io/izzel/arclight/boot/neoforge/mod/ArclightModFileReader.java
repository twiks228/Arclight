package io.izzel.arclight.boot.neoforge.mod;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.impl.JarContentsImpl;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.IOrderedProvider;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge {@link IModFileReader} that pre-filters {@link JarContentsImpl} before
 * any mod file readers attempt to parse jar metadata.
 *
 * <p>This reader runs at the highest system priority, so it executes before all
 * other readers. It delegates to {@link ArclightJarContentsImplFilter#filter}
 * to remove packages already provided by Arclight from the jar's package set,
 * preventing split-package errors in the module system.</p>
 *
 * <p>This reader always returns {@code null}, meaning it never actually claims
 * a jar as a mod file — it only performs the package filtering as a side effect.</p>
 */
public class ArclightModFileReader implements IModFileReader, IOrderedProvider {

    /**
     * Filters the jar's package set and returns {@code null} to let other
     * readers process the jar normally.
     *
     * @param jarContents                the jar contents to pre-filter
     * @param modFileDiscoveryAttributes discovery attributes (unused)
     * @return always {@code null} (this reader does not claim the file)
     */
    @Override
    public @Nullable IModFile read(
            JarContents jarContents,
            ModFileDiscoveryAttributes modFileDiscoveryAttributes) {
        // Filter packages before other readers analyze the jar's module descriptor
        ArclightJarContentsImplFilter.filter((JarContentsImpl) jarContents);
        return null;
    }

    /**
     * Returns the highest possible system priority to ensure this reader runs
     * before all other mod file readers.
     */
    @Override
    public int getPriority() {
        return HIGHEST_SYSTEM_PRIORITY;
    }
}