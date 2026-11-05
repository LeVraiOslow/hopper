package fr.chunkhopper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChunkHopperPlugin extends JavaPlugin implements TabExecutor {

    public record ChunkKey(UUID world, int x, int z) {}
    public record BlockPos(int x, int y, int z) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private NamespacedKey key;
    private int maxPerChunk;
    /** Index des Chunk Hoppers dans les chunks charges. */
    private final Map<ChunkKey, Set<BlockPos>> hoppers = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "chunk_hopper");
        maxPerChunk = getConfig().getInt("max-per-chunk", 0);

        getServer().getPluginManager().registerEvents(new HopperListener(this), this);

        PluginCommand cmd = getCommand("chunkhopper");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunk(chunk);
            }
        }

        long interval = Math.max(1L, getConfig().getLong("collect-interval-ticks", 5L));
        getServer().getScheduler().runTaskTimer(this, this::collectAll, interval, interval);
    }

    // ------------------------------------------------------------------ item

    public NamespacedKey key() {
        return key;
    }

    public ItemStack createItem(int amount) {
        ItemStack stack = new ItemStack(Material.HOPPER, amount);
        stack.editMeta(meta -> {
            meta.displayName(noItalic(MM.deserialize(getConfig().getString("item.name", "<gold>Chunk Hopper"))));
            List<Component> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("item.lore")) {
                lore.add(noItalic(MM.deserialize(line)));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    public boolean isChunkHopperItem(ItemStack stack) {
        return stack != null
                && stack.getType() == Material.HOPPER
                && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    public Component message(String path, Map<String, String> placeholders) {
        var builder = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.builder();
        placeholders.forEach((k, v) -> builder.resolver(Placeholder.unparsed(k, v)));
        return MM.deserialize(getConfig().getString("messages." + path, path), builder.build());
    }

    // ----------------------------------------------------------------- index

    private static ChunkKey keyOf(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    private static ChunkKey keyOf(Block block) {
        return new ChunkKey(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
    }

    public void scanChunk(Chunk chunk) {
        Set<BlockPos> found = new HashSet<>();
        for (BlockState state : chunk.getTileEntities(b -> b.getType() == Material.HOPPER, false)) {
            if (state instanceof Hopper hopper
                    && hopper.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                found.add(new BlockPos(state.getX(), state.getY(), state.getZ()));
            }
        }
        if (found.isEmpty()) {
            hoppers.remove(keyOf(chunk));
        } else {
            hoppers.put(keyOf(chunk), found);
        }
    }

    public void forgetChunk(Chunk chunk) {
        hoppers.remove(keyOf(chunk));
    }

    public boolean canPlaceIn(Block block) {
        if (maxPerChunk <= 0) return true;
        Set<BlockPos> set = hoppers.get(keyOf(block));
        return set == null || set.size() < maxPerChunk;
    }

    public void register(Block block) {
        hoppers.computeIfAbsent(keyOf(block), k -> new HashSet<>())
                .add(new BlockPos(block.getX(), block.getY(), block.getZ()));
    }

    public void unregister(Block block) {
        ChunkKey ck = keyOf(block);
        Set<BlockPos> set = hoppers.get(ck);
        if (set == null) return;
        set.remove(new BlockPos(block.getX(), block.getY(), block.getZ()));
        if (set.isEmpty()) hoppers.remove(ck);
    }

    // ------------------------------------------------------------ collecting

    private void collectAll() {
        Iterator<Map.Entry<ChunkKey, Set<BlockPos>>> iterator = hoppers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkKey, Set<BlockPos>> entry = iterator.next();
            ChunkKey ck = entry.getKey();
            World world = getServer().getWorld(ck.world());
            if (world == null || !world.isChunkLoaded(ck.x(), ck.z())) {
                iterator.remove();
                continue;
            }
            Chunk chunk = world.getChunkAt(ck.x(), ck.z(), false);
            if (!chunk.isEntitiesLoaded()) continue;

            // Recupere les inventaires des hoppers actifs et nettoie ceux qui n'existent plus.
            List<Inventory> inventories = new ArrayList<>();
            entry.getValue().removeIf(pos -> {
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                if (block.getType() != Material.HOPPER) return true;
                // Comme un hopper vanilla : verrouille par redstone = ne collecte pas.
                if (block.getBlockData() instanceof org.bukkit.block.data.type.Hopper data && !data.isEnabled()) {
                    return false;
                }
                if (block.getState(false) instanceof Hopper hopper) {
                    inventories.add(hopper.getInventory());
                }
                return false;
            });
            if (entry.getValue().isEmpty()) {
                iterator.remove();
                continue;
            }
            if (inventories.isEmpty()) continue;

            for (Entity entity : chunk.getEntities()) {
                if (!(entity instanceof Item item) || !item.isValid()) continue;

                ItemStack original = item.getItemStack();
                ItemStack remaining = original.clone();
                for (Inventory inventory : inventories) {
                    Map<Integer, ItemStack> left = inventory.addItem(remaining);
                    if (left.isEmpty()) {
                        remaining = null;
                        break;
                    }
                    remaining = left.get(0);
                }

                if (remaining == null) {
                    item.remove();
                } else if (remaining.getAmount() != original.getAmount()) {
                    item.setItemStack(remaining);
                }
            }
        }
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chunkhopper.give")) return true;
        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(message("usage", Map.of()));
            return true;
        }
        Player target = getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(message("unknown-player", Map.of()));
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                sender.sendMessage(message("usage", Map.of()));
                return true;
            }
        }
        target.getInventory().addItem(createItem(amount)).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(message("given", Map.of("amount", String.valueOf(amount), "player", target.getName())));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("chunkhopper.give")) return List.of();
        if (args.length == 1) return List.of("give");
        if (args.length == 2) {
            return getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3) return List.of("1", "16", "64");
        return List.of();
    }
}
