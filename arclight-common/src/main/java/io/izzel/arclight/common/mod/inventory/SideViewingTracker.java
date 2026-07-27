package io.izzel.arclight.common.mod.inventory;

import net.minecraft.world.Container;
import org.bukkit.entity.HumanEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks which HumanEntities are currently viewing a given Container.
 * Designed to be thread-safe for both modifications and iterations.
 */
public class SideViewingTracker {

    /**
     * WeakHashMap ensures that when a Container is garbage collected,
     * its associated viewer list is also removed, preventing memory leaks.
     */
    private static final Map<Container, List<HumanEntity>> VIEWERS = 
        Collections.synchronizedMap(new WeakHashMap<>());

    public static void onOpen(Container container, HumanEntity humanEntity) {
        // Use CopyOnWriteArrayList to prevent ConcurrentModificationException 
        // during viewer iteration by Bukkit/Mods.
        VIEWERS.computeIfAbsent(container, k -> new CopyOnWriteArrayList<>()).add(humanEntity);
    }

    public static void onClose(Container container, HumanEntity humanEntity) {
        List<HumanEntity> list = VIEWERS.get(container);
        if (list != null) {
            list.remove(humanEntity);
            // Optionally clean up empty lists to keep map size minimal
            if (list.isEmpty()) {
                VIEWERS.remove(container);
            }
        }
    }

    public static List<HumanEntity> getViewers(Container container) {
        return VIEWERS.computeIfAbsent(container, k -> new CopyOnWriteArrayList<>());
    }
}