package io.izzel.arclight.common.mod.mixins;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Predicate;

/**
 * Validates whether a given mixin class should be applied based on platform
 * and mod loading conditions.
 */
public class ShouldApplyProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShouldApplyProcessor.class);

    private static final List<Predicate<ClassNode>> PREDICATES = List.of(
        PlatformMixinProcessor::shouldApply,
        LoadIfModProcessor::shouldApply
    );

    public static boolean shouldApply(String mixinClass) {
        String resourcePath = mixinClass.replace('.', '/') + ".class";
        
        try (InputStream stream = PlatformMixinProcessor.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream != null) {
                byte[] bytes = stream.readAllBytes();
                ClassReader cr = new ClassReader(bytes);
                ClassNode node = new ClassNode();
                
                // SKIP_CODE is used because we only need annotations, not method bodies
                cr.accept(node, ClassReader.SKIP_CODE);
                
                for (Predicate<ClassNode> predicate : PREDICATES) {
                    if (!predicate.test(node)) {
                        return false;
                    }
                }
                return true;
            } else {
                LOGGER.debug("Mixin class resource not found: {}", mixinClass);
            }
            // Defaults to true if class stream cannot be located
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to read mixin class: {}", mixinClass, e);
            return true;
        }
    }
}