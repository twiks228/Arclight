package io.izzel.arclight.boot.neoforge.mod;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * NeoForge {@link IModFileCandidateLocator} that provides the Arclight common jar
 * to the NeoForge mod discovery pipeline.
 *
 * <p>On construction, triggers the Arclight bootstrap sequence via
 * {@link ModBootstrap#run()}, which installs artifacts and injects the
 * {@link ArclightImplementer} launch plugin.</p>
 *
 * <p>The common jar is expected to have been extracted to
 * {@code .arclight/mod_file/<version>.jar} by the bootstrap.</p>
 */
public class ArclightLocator_Neoforge implements IModFileCandidateLocator {

    /** Path to the extracted Arclight common jar. */
    private final Path arclight;

    /**
     * Constructs the locator and triggers the Arclight bootstrap.
     * The bootstrap installs Minecraft/Spigot artifacts and registers
     * the Arclight launch plugin.
     */
    public ArclightLocator_Neoforge() {
        ModBootstrap.run();
        this.arclight = loadJar();
    }

    /**
     * Resolves the path to the extracted common jar.
     * The version is read from the {@code arclight.version} system property,
     * which is set by {@link AbstractBootstrap#setupMod} during bootstrap.
     *
     * @return the path to the common jar under {@code .arclight/mod_file/}
     */
    protected Path loadJar() {
        String version = System.getProperty("arclight.version");
        return Paths.get(".arclight", "mod_file", version + ".jar");
    }

    /**
     * Adds the Arclight common jar to the NeoForge discovery pipeline.
     * Uses {@link IncompatibleFileReporting#WARN_ALWAYS} to log a warning
     * rather than crashing if the jar is not a valid NeoForge mod file
     * (e.g., during first-run artifact installation).
     *
     * @param context  the launch context (unused)
     * @param pipeline the discovery pipeline to add the jar to
     */
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        pipeline.addPath(
            this.arclight,
            ModFileDiscoveryAttributes.DEFAULT,
            IncompatibleFileReporting.WARN_ALWAYS
        );
    }

    @Override
    public String toString() {
        return "arclight";
    }
}