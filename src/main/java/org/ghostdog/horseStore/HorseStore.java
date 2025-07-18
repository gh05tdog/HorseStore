package org.ghostdog.horseStore;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class HorseStore extends JavaPlugin implements Listener, CommandExecutor {
    private Gson gson;
    private Map<UUID, Set<UUID>> playerHorses = new HashMap<>();
    private static final double MAX_HORSE_DISTANCE = 50.0; // Maximum distance in blocks before auto-storing

    @Override
    public void onEnable() {
        gson = new Gson();
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("horse").setExecutor(this);

        // Start a task to check horse distances periodically
        new BukkitRunnable() {
            @Override
            public void run() {
                checkHorseDistances();
            }
        }.runTaskTimer(this, 20L * 10, 20L * 10); // Check every 10 seconds
    }

    /**
     * Checks the distance between players and their horses.
     * If a horse is too far from its owner, it will be automatically stored.
     */
    private void checkHorseDistances() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerUUID = player.getUniqueId();
            Set<UUID> horses = playerHorses.get(playerUUID);

            if (horses == null || horses.isEmpty()) {
                continue;
            }

            // Create a copy to avoid ConcurrentModificationException
            Set<UUID> horsesToCheck = new HashSet<>(horses);

            for (UUID horseUUID : horsesToCheck) {
                Entity entity = Bukkit.getEntity(horseUUID);
                if (entity instanceof Horse horse) {
                    // Check if horse is too far from player
                    double distance = horse.getLocation().distance(player.getLocation());
                    if (distance > MAX_HORSE_DISTANCE) {
                        // Auto-store the horse
                        autoStoreHorse(player, horse);
                    }
                } else {
                    // Horse entity no longer exists, remove from tracking
                    horses.remove(horseUUID);
                }
            }
        }
    }

    /**
     * Automatically stores a horse for a player in the first available slot.
     * 
     * @param player The player who owns the horse
     * @param horse The horse to store
     */
    private void autoStoreHorse(Player player, Horse horse) {
        // Find the first available slot
        int slot = -1;
        for (int i = 1; i <= 10; i++) {
            if (loadHorseData(player, i) == null) {
                slot = i;
                break;
            }
        }

        // If no empty slot found, use slot 1 (overwrite)
        if (slot == -1) {
            slot = 1;
        }

        // Store the horse
        HorseData data = HorseData.fromEntity(horse);
        saveHorseData(player, slot, data);

        // Remove the horse from the world and tracking
        UUID horseUUID = horse.getUniqueId();
        if (playerHorses.containsKey(player.getUniqueId())) {
            playerHorses.get(player.getUniqueId()).remove(horseUUID);
        }
        horse.remove();

        player.sendMessage("§aYour horse was too far away and has been automatically stored in slot " + slot);
    }

    private NamespacedKey slotKey(int slot) {
        return new NamespacedKey(this, "horse_slot_" + slot);
    }

    private void saveHorseData(Player p, int slot, HorseData data) {
        String json = gson.toJson(data);
        p.getPersistentDataContainer()
                .set(slotKey(slot), PersistentDataType.STRING, json);
    }

    private HorseData loadHorseData(Player p, int slot) {
        var dc = p.getPersistentDataContainer();
        if (!dc.has(slotKey(slot), PersistentDataType.STRING)) return null;
        try {
            return gson.fromJson(dc.get(slotKey(slot), PersistentDataType.STRING), HorseData.class);
        } catch (JsonSyntaxException ex) {
            getLogger().warning("Could not parse horse data for " + p.getName() + " slot " + slot);
            return null;
        }
    }

    private void deleteHorseData(Player p, int slot) {
        p.getPersistentDataContainer().remove(slotKey(slot));
    }

    private List<Integer> listSlots(Player p) {
        return p.getPersistentDataContainer().getKeys().stream()
                .map(NamespacedKey::getKey)
                .filter(k -> k.startsWith("horse_slot_"))
                .map(k -> Integer.parseInt(k.substring("horse_slot_".length())))
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;
        if (args.length < 1) return usage(p);

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "store":
                if (args.length < 2) return usage(p);
                int slot;
                try { slot = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) { return usage(p); }
                if (slot < 1 || slot > 10) return usage(p);

                // find nearest owned, tamed horse
                Horse found = null;
                for (Entity e : p.getNearbyEntities(5, 5, 5)) {
                    if (e instanceof Horse h &&
                            h.isTamed() &&
                            p.getUniqueId().equals(h.getOwner().getUniqueId())) {
                        found = h;
                        break;
                    }
                }
                if (found == null) {
                    p.sendMessage("§cNo tamed horse nearby!");
                    return true;
                }

                // Check if slot is already used
                if (loadHorseData(p, slot) != null) {
                    p.sendMessage("§cWarning: Slot " + slot + " is already used.");
                }

                HorseData data = HorseData.fromEntity(found);
                saveHorseData(p, slot, data);

                // Remove from tracking before removing from world
                UUID horseUUID = found.getUniqueId();
                if (playerHorses.containsKey(p.getUniqueId())) {
                    playerHorses.get(p.getUniqueId()).remove(horseUUID);
                }

                found.remove();
                p.sendMessage("§aHorse stored in slot " + slot);
                break;

            case "spawn":
                if (args.length < 2) return usage(p);
                try { slot = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) { return usage(p); }

                HorseData hd = loadHorseData(p, slot);
                if (hd == null) {
                    p.sendMessage("§cNo horse in slot " + slot);
                    return true;
                }

                // Use the player's current world instead of the stored world
                World w = p.getWorld();

                // Spawn the horse beside the player instead of at its original location
                Location playerLoc = p.getLocation();
                // Offset the location slightly to avoid spawning inside the player
                // Get the direction the player is facing and move 2 blocks in that direction
                double offsetX = Math.sin(Math.toRadians(playerLoc.getYaw())) * -2;
                double offsetZ = Math.cos(Math.toRadians(playerLoc.getYaw())) * 2;
                playerLoc.add(offsetX, 0, offsetZ);

                Horse h = (Horse) w.spawnEntity(
                        playerLoc,
                        EntityType.HORSE
                );
                hd.applyToEntity(h);
                h.setOwner(p);

                // Add the horse to tracking
                UUID playerUUID = p.getUniqueId();
                UUID spawnedHorseUUID = h.getUniqueId();
                playerHorses.computeIfAbsent(playerUUID, k -> new HashSet<>()).add(spawnedHorseUUID);

                // Delete horse data after spawning so it can only be spawned once
                deleteHorseData(p, slot);
                p.sendMessage("§aHorse spawned beside you from slot " + slot + " and removed from storage");
                break;

            case "kill":
                if (args.length < 2) return usage(p);
                try { slot = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) { return usage(p); }
                deleteHorseData(p, slot);
                p.sendMessage("§cDeleted stored horse in slot " + slot);
                break;

            case "list":
                List<Integer> slots = listSlots(p);
                if (slots.isEmpty()) {
                    p.sendMessage("§7You don't have any stored horses.");
                } else {
                    p.sendMessage("§6§l=== Your Stored Horses ===");
                    for (int slotNum : slots) {
                        HorseData horseData = loadHorseData(p, slotNum);
                        if (horseData != null) {
                            String horseName = horseData.customName != null ? 
                                "§e\"" + horseData.customName + "§e\"" : "§7Unnamed";
                            p.sendMessage(String.format("§a#%d: %s §7- %s %s", 
                                slotNum, horseName, horseData.color, horseData.style));
                        } else {
                            p.sendMessage(String.format("§a#%d: §cData corrupted", slotNum));
                        }
                    }
                }
                break;

            default:
                return usage(p);
        }
        return true;
    }

    private boolean usage(Player p) {
        p.sendMessage("§cUsage: /horse <spawn|store|kill|list> [1–10]");
        return true;
    }

    /**
     * Event handler for when a player joins the server.
     * Initializes the player's horse tracking set if needed.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Initialize the player's horse tracking set if it doesn't exist
        if (!playerHorses.containsKey(playerUUID)) {
            playerHorses.put(playerUUID, new HashSet<>());
        }
    }

    /**
     * Event handler for when a player quits the server.
     * Cleans up the player's horse tracking data.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Auto-store all of the player's horses before they leave
        Set<UUID> horses = playerHorses.get(playerUUID);
        if (horses != null && !horses.isEmpty()) {
            // Create a copy to avoid ConcurrentModificationException
            Set<UUID> horsesToStore = new HashSet<>(horses);

            for (UUID horseUUID : horsesToStore) {
                Entity entity = Bukkit.getEntity(horseUUID);
                if (entity instanceof Horse horse) {
                    autoStoreHorse(player, horse);
                }
            }
        }

        // Remove the player from tracking
        playerHorses.remove(playerUUID);
    }

    /**
     * Event handler for when a chunk unloads.
     * Checks if any tracked horses are in the unloading chunk and auto-stores them.
     */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();

        // Check all entities in the chunk
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Horse horse && horse.isTamed() && horse.getOwner() != null) {
                UUID horseUUID = horse.getUniqueId();
                UUID ownerUUID = horse.getOwner().getUniqueId();

                // Check if this horse is being tracked
                if (playerHorses.containsKey(ownerUUID) && 
                    playerHorses.get(ownerUUID).contains(horseUUID)) {

                    // Get the owner if they're online
                    Player owner = Bukkit.getPlayer(ownerUUID);
                    if (owner != null && owner.isOnline()) {
                        // Auto-store the horse
                        autoStoreHorse(owner, horse);
                    }
                }
            }
        }
    }

    // --- Data holder + serialization logic ---
    public static class HorseData {
        public String world;
        public double x, y, z;
        public double speed, jump;
        public String color, style;
        public String customName;    // may be null
        public String inventory;     // Base64-encoded ItemStack array

        public static HorseData fromEntity(Horse h) {
            HorseData d = new HorseData();
            d.world      = h.getWorld().getName();
            Location loc = h.getLocation();
            d.x = loc.getX(); d.y = loc.getY(); d.z = loc.getZ();
            d.speed      = h.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue();
            d.jump       = h.getAttribute(Attribute.JUMP_STRENGTH).getBaseValue();
            d.color      = h.getColor().name();
            d.style      = h.getStyle().name();
            d.customName = h.getCustomName();
            d.inventory  = toBase64(h.getInventory().getContents());
            return d;
        }

        public void applyToEntity(Horse h) {
            h.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed);
            h.getAttribute(Attribute.JUMP_STRENGTH).setBaseValue(jump);
            h.setColor(Horse.Color.valueOf(color));
            h.setStyle(Horse.Style.valueOf(style));
            if (customName != null) {
                h.setCustomName(customName);
                h.setCustomNameVisible(true);
            }
            try {
                ItemStack[] items = fromBase64(inventory);
                h.getInventory().setContents(items);
            } catch (IOException e) {
                // log and continue
                Bukkit.getLogger().warning("Failed loading horse inventory: " + e.getMessage());
            }
        }

        // Serialize an ItemStack array to a Base64 string
        private static String toBase64(ItemStack[] items) {
            try (ByteArrayOutputStream bout = new ByteArrayOutputStream();
                 BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bout)) {
                oos.writeInt(items.length);
                for (ItemStack is : items) oos.writeObject(is);
                return Base64.getEncoder().encodeToString(bout.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to serialize inventory", e);
            }
        }

        // Deserialize from Base64 back to ItemStack[]
        private static ItemStack[] fromBase64(String data) throws IOException {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (ByteArrayInputStream bin = new ByteArrayInputStream(bytes);
                 BukkitObjectInputStream ois = new BukkitObjectInputStream(bin)) {
                int len = ois.readInt();
                ItemStack[] items = new ItemStack[len];
                for (int i = 0; i < len; i++) {
                    items[i] = (ItemStack) ois.readObject();
                }
                return items;
            } catch (ClassNotFoundException e) {
                throw new IOException("Class not found in deserialization", e);
            }
        }
    }
}
