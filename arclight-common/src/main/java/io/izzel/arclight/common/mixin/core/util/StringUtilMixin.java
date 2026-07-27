package io.izzel.arclight.common.mixin.core.util;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.util.StringUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Mixin for {@link StringUtil} that allows Arclight to accept additional
 * player name formats through a configurable regular expression.
 *
 * <p>If the configured regex is blank or invalid, vanilla validation remains unchanged.
 * If the regex matches the provided name, this mixin forces the result to {@code true}.</p>
 */
@Mixin(value = StringUtil.class, priority = 1100)
public class StringUtilMixin {

    /**
     * Cached raw regex string from config to avoid recompiling the pattern
     * on every username validation call.
     */
    @Unique
    private static volatile String arclight$cachedRegex;

    /**
     * Cached compiled regex pattern corresponding to {@link #arclight$cachedRegex}.
     * May be {@code null} if the regex is blank or invalid.
     */
    @Unique
    private static volatile Pattern arclight$cachedPattern;

    /**
     * Returns {@code true} if the configured custom username regex is enabled
     * and the provided name matches it.
     *
     * <p>The pattern is cached and only recompiled if the config value changes.</p>
     *
     * @param name the username to validate
     * @return {@code true} if the custom regex accepts the name
     */
    @Unique
    private static boolean arclight$validUsernameCheck(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        String regex = ArclightConfig.spec().getCompat().getValidUsernameRegex();
        if (regex == null || regex.isBlank()) {
            return false;
        }

        Pattern pattern = arclight$cachedPattern;
        String cached = arclight$cachedRegex;

        if (!regex.equals(cached)) {
            try {
                pattern = Pattern.compile(regex);
                arclight$cachedRegex = regex;
                arclight$cachedPattern = pattern;
            } catch (PatternSyntaxException ex) {
                // Invalid config regex: disable custom validation until config changes
                arclight$cachedRegex = regex;
                arclight$cachedPattern = null;
                return false;
            }
        }

        return pattern != null && pattern.matcher(name).matches();
    }

    /**
     * Allows custom player names accepted by the configured Arclight regex
     * to bypass vanilla name validation.
     *
     * @param name the username being validated
     * @param cir  callback return value
     */
    @Inject(
        method = "isValidPlayerName",
        cancellable = true,
        at = @At("HEAD")
    )
    private static void arclight$checkUsername(String name, CallbackInfoReturnable<Boolean> cir) {
        if (arclight$validUsernameCheck(name)) {
            cir.setReturnValue(true);
        }
    }
}