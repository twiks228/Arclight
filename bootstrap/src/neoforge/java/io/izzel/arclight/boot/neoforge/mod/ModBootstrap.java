package io.izzel.arclight.boot.neoforge.mod;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.JarContentsBuilder;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.jarhandling.impl.Jar;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import cpw.mods.util.LambdaExceptionUtils;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.api.Unsafe;
import io.izzel.arclight.boot.AbstractBootstrap;
import io.izzel.arclight.installer.ForgeInstaller;
import io.izzel.arclight.installer.MinecraftProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.MarkerManager;

import java.io.File;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSigner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;

/**
 * Bootstrap entry point for the Arclight NeoForge hybrid server.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Installing Minecraft/Spigot artifacts via {@link MinecraftProvider}</li>
 *   <li>Loading the Arclight common jar into the NeoForge module system</li>
 *   <li>Injecting the {@link ArclightImplementer} launch plugin</li>
 *   <li>Wiring package lookups into the ModuleClassLoader after TX CL construction</li>
 * </ul>
 */
public class ModBootstrap implements AbstractBootstrap {

    /**
     * Holds the {@link Configuration} and parent {@link ClassLoader} needed by
     * {@link #postRun()} to wire package lookups after the transformer class
     * loader has been fully constructed.
     *
     * @param configuration the resolved module configuration containing the common jar
     * @param parent        the class loader to delegate package lookups to
     */
    public record ModBoot(Configuration configuration, ClassLoader parent) {}

    private static ModBoot modBoot;

    // ── Bootstrap entry point ─────────────────────────────────────────────────

    /**
     * Called by {@link ArclightLocator_Neoforge} during mod discovery.
     * Installs Minecraft artifacts, loads the common jar, and injects the
     * Arclight launch plugin into the NeoForge modlauncher pipeline.
     *
     * <p>If the {@code arclight_implementer} plugin is already registered
     * (e.g., from a previous run or another bootstrap path), this is a no-op.</p>
     */
    static void run() {
        // Guard against double-initialization (e.g., dev environment restarts)
        var plugin = Launcher.INSTANCE.environment().findLaunchPlugin("arclight_implementer");
        if (plugin.isPresent()) return;

        var logger = LogManager.getLogger("Arclight");
        var marker = MarkerManager.getMarker("INSTALL");
        try {
            var paths = MinecraftProvider.modInstall(s -> logger.info(marker, s));
            load(paths.toArray(new Path[0]));
            new ModBootstrap().inject();
        } catch (Throwable e) {
            logger.error("Error bootstrapping Arclight J2K", e);
            throw new RuntimeException(e);
        }
    }

    // ── Post-TX class loader wiring ───────────────────────────────────────────

    /**
     * Called by {@link ArclightImplementer#initializeLaunch} after the transformer
     * class loader has been constructed.
     *
     * <p>Wires package-to-classloader mappings so that packages from the common
     * jar are resolved through the parent class loader rather than through the
     * transformer class loader. This is necessary because the common jar is loaded
     * before the TX CL exists, but its packages must be accessible after.</p>
     */
    @SuppressWarnings("unchecked")
    public static void postRun() {
        if (modBoot == null) return;
        try {
            Configuration conf = modBoot.configuration();
            ClassLoader parent = modBoot.parent();
            var classLoader = (ModuleClassLoader) Thread.currentThread().getContextClassLoader();

            // Access the package → classloader delegation map
            var parentField = ModuleClassLoader.class.getDeclaredField("parentLoaders");
            var parentLoaders = (Map<String, ClassLoader>) Unsafe.getObject(
                classLoader, Unsafe.objectFieldOffset(parentField)
            );

            // Register all packages from the new configuration modules
            for (ResolvedModule mod : conf.modules()) {
                for (String pkg : mod.reference().descriptor().packages()) {
                    parentLoaders.put(pkg, parent);
                }
            }

            // Release the ModBoot reference so it can be GC'd
            modBoot = null;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to wire package lookups in ModBootstrap.postRun()", t);
        }
    }

    // ── Internal bootstrap sequence ───────────────────────────────────────────

    private void inject() throws Throwable {
        dirtyHacks();
        setupMod(ArclightPlatform.NEOFORGE);
        injectClassPath();
        injectLaunchPlugin();
    }

    /**
     * Adds all modules from the boot module layer to the classpath.
     * Required for libraries that use the platform class loader's UCP
     * (e.g., JDBC drivers loaded via {@code ServiceLoader}).
     */
    private void injectClassPath() throws Throwable {
        var platform = ClassLoader.getPlatformClassLoader();
        var ucpField = platform.getClass().getSuperclass().getDeclaredField("ucp");
        var ucp = Unsafe.lookup().unreflectGetter(ucpField).invoke(platform);

        if (ucp == null) {
            // UCP is not present (Java 17+ with named modules) — fall back to
            // adding each module location individually
            for (var module : ModuleLayer.boot().configuration().modules()) {
                var location = module.reference().location();
                if (location.isPresent()) {
                    URI uri = location.get();
                    if (uri.getScheme().equals("file")) {
                        ForgeInstaller.addToPath(new File(uri).toPath());
                    }
                }
            }
        }
    }

    /**
     * Injects the {@link ArclightImplementer} as a launch plugin into the
     * NeoForge modlauncher's {@link LaunchPluginHandler}.
     *
     * <p>This must be done via reflection because {@code plugins} is a private
     * field and no public API exists to register additional plugins after
     * initial setup.</p>
     */
    @SuppressWarnings("unchecked")
    private void injectLaunchPlugin() throws Exception {
        var launcher = Launcher.INSTANCE;

        var launchPluginsField = Launcher.class.getDeclaredField("launchPlugins");
        launchPluginsField.setAccessible(true);
        var handler = (LaunchPluginHandler) launchPluginsField.get(launcher);

        var pluginsField = LaunchPluginHandler.class.getDeclaredField("plugins");
        pluginsField.setAccessible(true);
        var map = (Map<String, ILaunchPluginService>) pluginsField.get(handler);

        var plugin = new ArclightImplementer();
        map.put(plugin.name(), plugin);
    }

    // ── Module loading ────────────────────────────────────────────────────────

    /**
     * Packages that should be excluded from the Arclight common jar when
     * loading it into the module system. These packages cause conflicts with
     * Maven artifact resolution libraries already present in the environment.
     */
    private static final Set<String> EXCLUDES = Set.of(
        "org/apache/maven/artifact/repository/metadata"
    );

    /**
     * Loads the given paths (the Arclight common jar contents) into the
     * NeoForge {@link ModuleClassLoader} by resolving a new module configuration
     * and wiring the package → module mappings.
     *
     * <p>The resolved configuration is stored in {@link #modBoot} for later
     * use by {@link #postRun()} after the TX class loader is constructed.</p>
     *
     * @param paths the jar paths to load (typically just the common jar)
     */
    @SuppressWarnings("unchecked")
    private static void load(Path[] paths) throws Throwable {
        var classLoader = (ModuleClassLoader) ModBootstrap.class.getClassLoader();

        // Build a SecureJar from the provided paths, excluding conflicting packages
        var secureJar = SecureJar.from(
            new JarContentsBuilder()
                .paths(paths)
                .pathFilter((entry, basePath) ->
                    EXCLUDES.stream().noneMatch(entry::startsWith))
                .build()
        );

        // Resolve a new module configuration on top of the existing one
        var configField = ModuleClassLoader.class.getDeclaredField("configuration");
        long confOffset = Unsafe.objectFieldOffset(configField);
        var oldConf = (Configuration) Unsafe.getObject(classLoader, confOffset);
        var newConf = oldConf.resolveAndBind(
            JarModuleFinder.of(secureJar),
            ModuleFinder.of(),
            List.of(secureJar.name())
        );

        // Store for postRun() — must happen before we overwrite the configuration
        modBoot = new ModBoot(newConf, classLoader);

        // Install the new configuration (volatile write for visibility across threads)
        Unsafe.putObjectVolatile(classLoader, confOffset, newConf);

        // Wire package → resolved module mappings
        var pkgField = ModuleClassLoader.class.getDeclaredField("packageLookup");
        var packageLookup = (Map<String, ResolvedModule>) Unsafe.getObject(
            classLoader, Unsafe.objectFieldOffset(pkgField)
        );

        // Wire module name → module reference mappings (for class loading)
        var rootField = ModuleClassLoader.class.getDeclaredField("resolvedRoots");
        var resolvedRoots = (Map<String, Object>) Unsafe.getObject(
            classLoader, Unsafe.objectFieldOffset(rootField)
        );

        // Construct a JarModuleReference for the new module
        var moduleRefCtor = Unsafe.lookup().findConstructor(
            Class.forName("cpw.mods.cl.JarModuleFinder$JarModuleReference"),
            MethodType.methodType(void.class, SecureJar.ModuleDataProvider.class)
        );

        for (ResolvedModule mod : newConf.modules()) {
            // Register all packages for this module
            for (String pkg : mod.reference().descriptor().packages()) {
                packageLookup.put(pkg, mod);
            }
            // Register the module reference so the class loader can open it
            resolvedRoots.put(
                mod.name(),
                moduleRefCtor.invokeWithArguments(
                    new JarModuleDataProvider((Jar) secureJar)
                )
            );
        }
    }

    // ── ModuleDataProvider adapter ────────────────────────────────────────────

    /**
     * Bridges Arclight's {@link Jar} (from cpw.mods.jarhandling) into the
     * {@link SecureJar.ModuleDataProvider} interface required by the module
     * reference constructor.
     *
     * <p>Used to wrap the SecureJar so it can be registered as a module root
     * in the ModuleClassLoader's resolved-roots map.</p>
     */
    private record JarModuleDataProvider(Jar jar) implements SecureJar.ModuleDataProvider {

        @Override
        public String name() {
            return jar.name();
        }

        @Override
        public ModuleDescriptor descriptor() {
            return jar.computeDescriptor();
        }

        @Override
        public URI uri() {
            return jar.getURI();
        }

        @Override
        public Optional<URI> findFile(String name) {
            return jar.findFile(name);
        }

        @Override
        public Optional<InputStream> open(String name) {
            // Open the file as a stream, re-throwing any IOException as unchecked
            return jar.findFile(name)
                .map(Paths::get)
                .map(LambdaExceptionUtils.rethrowFunction(Files::newInputStream));
        }

        @Override
        public Manifest getManifest() {
            return jar.moduleDataProvider().getManifest();
        }

        @Override
        public CodeSigner[] verifyAndGetSigners(String cname, byte[] bytes) {
            return jar.moduleDataProvider().verifyAndGetSigners(cname, bytes);
        }
    }
}