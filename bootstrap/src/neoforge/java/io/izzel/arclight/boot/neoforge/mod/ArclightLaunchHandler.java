package io.izzel.arclight.boot.neoforge.mod;

import net.neoforged.fml.loading.targets.NeoForgeServerLaunchHandler;

/**
 * Custom NeoForge server launch handler for Arclight J2K.
 *
 * <p>Extends {@link NeoForgeServerLaunchHandler} to register under the
 * {@code "arclightserver"} target name. This allows the NeoForge launch system
 * to select this handler when the server is launched with
 * {@code --launchTarget arclightserver}.</p>
 *
 * <p>The {@link #preLaunch} override skips NeoForge's default Log4j configuration
 * reload, which would reset Arclight's custom logging setup (i18n appenders,
 * coloured console, etc.).</p>
 */
public class ArclightLaunchHandler extends NeoForgeServerLaunchHandler {

    @Override
    public String name() {
        return "arclightserver";
    }

    /**
     * Skips the default Log4j configuration reload that NeoForge normally
     * performs during pre-launch. Arclight configures logging earlier in the
     * bootstrap sequence and must not have its configuration overwritten.
     *
     * @param arguments the command-line arguments passed to the server
     * @param layer     the module layer (unused)
     * @return the original arguments, unmodified
     */
    @Override
    protected String[] preLaunch(String[] arguments, ModuleLayer layer) {
        return arguments;
    }
}