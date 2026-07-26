package io.izzel.arclight.boot.neoforge.application;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.api.EnumHelper;
import io.izzel.arclight.api.Unsafe;
import io.izzel.arclight.boot.AbstractBootstrap;
import io.izzel.arclight.boot.diagnostic.ConflictDetector;
import io.izzel.arclight.boot.diagnostic.StartupDiagnostics;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.ArclightLocale;

import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.function.Consumer;

public class ApplicationBootstrap implements Consumer<String[]>, AbstractBootstrap {

    private static final int MIN_DEPRECATED_VERSION    = 60;
    private static final int MIN_DEPRECATED_JAVA_VERSION = 16;

    @Override
    @SuppressWarnings("unchecked")
    public void accept(String[] args) {
        System.setProperty("java.util.logging.manager",
            "org.apache.logging.log4j.jul.LogManager");
        System.setProperty("log4j.jul.LoggerAdapter",
            "io.izzel.arclight.boot.log.ArclightLoggerAdapter");
        System.setProperty("log4j.configurationFile", "arclight-log4j2.xml");

        ArclightLocale.info("i18n.using-language",
            ArclightConfig.spec().getLocale().getCurrent(),
            ArclightConfig.spec().getLocale().getFallback());

        try {
            int javaVersion = (int) Float.parseFloat(
                System.getProperty("java.class.version"));
            if (javaVersion < MIN_DEPRECATED_VERSION) {
                ArclightLocale.error("java.deprecated",
                    System.getProperty("java.version"), MIN_DEPRECATED_JAVA_VERSION);
                Thread.sleep(3000);
            }
            Unsafe.ensureClassInitialized(EnumHelper.class);
        } catch (Throwable t) {
            System.err.println("Your Java is not compatible with Arclight.");
            t.printStackTrace();
            return;
        }

        try {
            // 1. Standard Arclight initialization
            this.setupMod(ArclightPlatform.NEOFORGE);
            this.dirtyHacks();

            // 2. Read actual NeoForge and Minecraft versions from FML launch args.
            // FML passes them as: --fml.neoForgeVersion 21.1.236 --fml.mcVersion 1.21.1
            String neoforgeVersion = extractArg(args, "--fml.neoForgeVersion", "21.1.x");
            String minecraftVersion = extractArg(args, "--fml.mcVersion",      "1.21.1");

            // Store for later use by other components (e.g. /j2k status)
            System.setProperty("arclight.neoforge.version", neoforgeVersion);
            System.setProperty("arclight.minecraft.version", minecraftVersion);

            // 3. Print early diagnostics banner (before mods load)
            StartupDiagnostics.printEarlyDiagnostics(neoforgeVersion, minecraftVersion);

            // 4. Scan mods/ and plugins/ for known conflicts
            ConflictDetector.ScanResult scanResult = ConflictDetector.scan();

            if (scanResult.hasErrors()) {
                System.err.println("[Arclight-J2K] Critical conflicts detected ("
                    + scanResult.errorCount()
                    + ")! Check the log above before proceeding.");
                Thread.sleep(2000);
            }

            // 5. Standard NeoForge launch (unchanged)
            int targetIndex = Arrays.asList(args).indexOf("--launchTarget");
            if (targetIndex >= 0 && targetIndex < args.length - 1) {
                args[targetIndex + 1] = "arclightserver";
            }
            ServiceLoader.load(getClass().getModule().getLayer(), Consumer.class)
                    .stream()
                    .filter(it -> !it.type().getName().contains("arclight"))
                    .findFirst()
                    .orElseThrow()
                    .get()
                    .accept(args);

            // 6. Full diagnostics after server and plugins are ready (5-second delay)
            scheduleFullDiagnostics(neoforgeVersion, minecraftVersion);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Fail to launch Arclight.");
        }
    }

    /**
     * Schedules full diagnostics to run 5 seconds after startup.
     * By that time Paper has finished loading all plugins.
     */
    private void scheduleFullDiagnostics(String neoforgeVersion, String minecraftVersion) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(5000);
                int modCount    = StartupDiagnostics.countModFiles();
                int pluginCount = StartupDiagnostics.countPluginFiles();
                StartupDiagnostics.printDiagnostics(
                    neoforgeVersion, minecraftVersion, modCount, pluginCount);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Arclight-J2K-Diagnostics");

        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Extracts a named argument value from the launch args array.
     * Example: extractArg(args, "--fml.neoForgeVersion", "unknown") → "21.1.236"
     */
    private static String extractArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }
}