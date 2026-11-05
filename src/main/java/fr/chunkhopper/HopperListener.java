package fr.chunkhopper;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

public final class HopperListener implements Listener {

    private final ChunkHopperPlugin plugin;

    public HopperListener(ChunkHopperPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.HOPPER) return;

        if (!plugin.isChunkHopperItem(event.getItemInHand())) {
            plugin.unregister(block); // hopper normal pose a une ancienne position
            return;
        }
        if (!plugin.canPlaceIn(block)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("max-reached", Map.of()));
            return;
        }
        if (block.getState() instanceof Hopper hopper) {
            hopper.getPersistentDataContainer().set(plugin.key(), PersistentDataType.BYTE, (byte) 1);
            hopper.update();
        }
        plugin.register(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        plugin.unregister(event.getBlock());
    }

    /** Le hopper casse redonne un Chunk Hopper (et pas un hopper vanilla). */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        if (!(event.getBlockState() instanceof Hopper hopper)) return;
        if (!hopper.getPersistentDataContainer().has(plugin.key(), PersistentDataType.BYTE)) return;
        for (Item item : event.getItems()) {
            if (item.getItemStack().getType() == Material.HOPPER) {
                item.setItemStack(plugin.createItem(1));
                break;
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.scanChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.forgetChunk(event.getChunk());
    }
}
