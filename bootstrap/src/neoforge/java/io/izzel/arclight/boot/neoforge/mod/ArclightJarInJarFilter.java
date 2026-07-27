package io.izzel.arclight.boot.neoforge.mod;

import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IOrderedProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * NeoForge {@link IDependencyLocator} that removes Jar-in-Jar (JiJ) dependencies
 * from the mod discovery pipeline when Arclight's module layer already provides
 * the same module.
 *
 * <p>NeoForge mods often bundle their dependencies as JiJ entries. On a hybrid
 * server, Arclight's module layer already contains many of these libraries
 * (e.g., Guava, Gson, ASM). Loading them again from JiJ causes split-package
 * errors and class loading conflicts.</p>
 *
 * <p>This locator runs at the lowest system priority (after all JiJ entries have
 * been extracted) and removes any extracted module whose name matches a module
 * already present in Arclight's module layer.</p>
 */
public class ArclightJarInJarFilter implements IDependencyLocator, IOrderedProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("ArclightJiJ");

    /**
     * Removes JiJ dependencies from the discovery pipeline when Arclight's
     * module layer already provides those modules.
     *
     * <p>Uses reflection to access the pipeline's internal {@code loadedFiles}
     * list, which is not exposed via the public API.</p>
     *
     * @param loadedMods the list of already-loaded mod files
     * @param pipeline   the discovery pipeline containing extracted JiJ files
     */
    @SuppressWarnings("unchecked")
    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        try {
            var field = pipeline.getClass().getDeclaredField("loadedFiles");
            field.setAccessible(true);
            var loadedFiles = (List<ModFile>) field.get(pipeline);

            loadedFiles.removeIf(modFile -> {
                String moduleName = modFile.getModFileInfo().moduleName();
                // Check if Arclight's module layer already has this module
                var existingModule = getClass().getModule().getLayer().findModule(moduleName);
                existingModule.ifPresent(module ->
                    LOGGER.info(
                        "Skipping JiJ dependency {}@{} — Arclight layer already has {}",
                        moduleName,
                        modFile.getModFileInfo().versionString(),
                        module.getDescriptor().toNameAndVersion()
                    )
                );
                return existingModule.isPresent();
            });
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to filter JiJ dependencies in ArclightJarInJarFilter", e
            );
        }
    }

    @Override
    public String toString() {
        return "arclight_jij";
    }

    /**
     * Runs at the lowest system priority so all JiJ entries are extracted and
     * registered before this filter removes duplicates.
     */
    @Override
    public int getPriority() {
        return IOrderedProvider.LOWEST_SYSTEM_PRIORITY;
    }
}