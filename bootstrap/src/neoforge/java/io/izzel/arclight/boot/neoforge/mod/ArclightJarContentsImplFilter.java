package io.izzel.arclight.boot.neoforge.mod;

import cpw.mods.jarhandling.impl.JarContentsImpl;
import io.izzel.arclight.api.Unsafe;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filters package sets from third-party {@link JarContentsImpl} instances to
 * prevent split-package conflicts with packages already provided by Arclight's
 * service module layer.
 *
 * <p><b>Problem:</b> NeoForge's module system requires that each package is
 * provided by exactly one module (the Java Platform Module System rule). When
 * a mod's jar contains packages that Arclight's bootstrap layer already exports,
 * the module system throws a {@code FindException} or {@code LayerInstantiationException}.</p>
 *
 * <p><b>Solution:</b> Before NeoForge processes a mod's jar metadata, this filter
 * removes conflicting packages from the cached package set inside {@link JarContentsImpl}.
 * The cache is accessible via the private {@code packages} field, which we access
 * using {@link Unsafe} to bypass JPMS accessibility restrictions.</p>
 *
 * <p><b>Scope:</b> Only library-style modules are affected. Mod modules use
 * {@code getPackagesExcluding(String...)} which bypasses the cache, so they are
 * handled separately by {@link ArclightJarInJarFilter}.</p>
 */
public class ArclightJarContentsImplFilter {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    /**
     * {@link VarHandle} for the private {@code packages} field of {@link JarContentsImpl}.
     * Initialized once at class load time.
     */
    private static final VarHandle PACKAGES;

    /**
     * The complete set of packages already exported by all modules in Arclight's
     * service layer. Used as the exclusion set when filtering third-party jars.
     */
    private static final Set<String> SERVICE_LAYER_PACKAGES;

    static {
        VarHandle handle = null;
        try {
            MethodHandles.Lookup lookup = Unsafe.lookup();
            handle = lookup.findVarHandle(JarContentsImpl.class, "packages", Set.class);
        } catch (ReflectiveOperationException e) {
            LOGGER.error(
                "Arclight failed to access JarContentsImpl.packages via VarHandle. "
                + "This may cause dependency conflicts with some mods!", e
            );
        }
        PACKAGES = handle;

        // Collect all packages from all modules in the Arclight service layer
        SERVICE_LAYER_PACKAGES = ArclightJarContentsImplFilter.class
            .getModule()
            .getLayer()
            .modules()
            .stream()
            .flatMap(module -> module.getPackages().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Filters the given {@link JarContentsImpl}'s package set in-place, removing
     * any package that is already provided by Arclight's service layer.
     *
     * <p>The package set is cached inside {@link JarContentsImpl} after the first
     * call to {@code getPackages()}. By modifying this cache, we change what NeoForge
     * sees as the jar's package list without touching the underlying zip entries.</p>
     *
     * <p>Note: This method triggers the package cache initialization if it has
     * not already been populated (by calling {@code impl.getPackages()} first).</p>
     *
     * @param impl the jar contents to filter; must not be {@code null}
     */
    @SuppressWarnings("unchecked")
    public static void filter(JarContentsImpl impl) {
        if (PACKAGES == null) {
            // VarHandle not available — skip filtering to avoid crash
            return;
        }

        // Populate the cache if not yet initialized
        impl.getPackages();

        Set<String> rawPackages = (Set<String>) PACKAGES.get(impl);
        Set<String> filteredPackages = rawPackages.stream()
            .filter(pkg -> !SERVICE_LAYER_PACKAGES.contains(pkg))
            .collect(Collectors.toUnmodifiableSet());

        PACKAGES.set(impl, filteredPackages);
    }

    /**
     * Returns {@code true} if the given package is NOT already provided by
     * Arclight's service layer (i.e., it is safe to include in a mod's module).
     *
     * @param pkg the package name to test
     * @return {@code true} if the package is not in Arclight's layer
     */
    public static boolean test(String pkg) {
        return !SERVICE_LAYER_PACKAGES.contains(pkg);
    }
}