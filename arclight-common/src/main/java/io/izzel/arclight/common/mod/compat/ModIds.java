package io.izzel.arclight.common.mod.compat;

/**
 * Constants for known mod IDs to handle cross-mod compatibility issues.
 */
public final class ModIds {

    private ModIds() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static final String IMMERSIVE_PORTALS = "immersive_portals";

    // Known lithium forks
    public static final String LITHIUM = "lithium";
    public static final String CANARY  = "canary";
    public static final String RADIUM  = "radium";

    public static final String RECRUITS = "recruits";
}