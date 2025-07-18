package org.ghostdog.horseStore;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class horseStore extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Gson gson;
    private Connection connection;
    private int maxDistance;
    private int slotCount;
    private final Map<UUID, Set<UUID>> playerHorses = new HashMap<>();

    @Override
    public void onEnable() {
        String dbPath;
        int checkInterval;
        // Ensure data folder and config file exist
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()){
            if (dataFolder.mkdirs()) {
                getLogger().info("Data folder created successfully.");
            } else {
                getLogger().severe("Failed to create data folder!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            try {
                YamlConfiguration def = new YamlConfiguration();
                def.set("database.path", "plugins/HorseStore/horses.db");
                def.set("settings.max-distance", 50);
                def.set("settings.slots-count", 10);
                def.set("settings.check-interval-seconds", 10);
                def.save(configFile);
            } catch (IOException ex) {
                getLogger().log(Level.SEVERE, "Could not create default config", ex);
            }
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        dbPath = cfg.getString("database.path");
        maxDistance = cfg.getInt("settings.max-distance");
        slotCount = cfg.getInt("settings.slots-count");
        checkInterval = cfg.getInt("settings.check-interval-seconds");

        gson = new Gson();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("horse");
        assert command != null;
        command.setExecutor(this);
        command.setTabCompleter(this);

        // Initialize SQLite database
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS horse_data (" +
                                "player_uuid TEXT NOT NULL, " +
                                "slot INTEGER NOT NULL, " +
                                "data TEXT NOT NULL, " +
                                "PRIMARY KEY(player_uuid, slot))"
                );
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not initialize database", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Start a synchronous distance check task to avoid AsyncCatcher
        new BukkitRunnable() {
            @Override
            public void run() {
                checkHorseDistances();
            }
        }.runTaskTimer(this, 20L * checkInterval, 20L * checkInterval);
    }

    @Override
    public void onDisable() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
            getLogger().log(Level.WARNING, "Failed to close database connection");
        } finally {
            playerHorses.clear();
            getLogger().info("HorseStore plugin disabled successfully.");
        }
    }

    // --- Database operations ---
    private synchronized void saveHorseData(UUID playerId, int slot, HorseData data) {
        String json = gson.toJson(data);
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO horse_data(player_uuid, slot, data) VALUES(?,?,?)"
        )) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, slot);
            ps.setString(3, json);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to save horse data", e);
        }
    }

    private synchronized HorseData loadHorseData(UUID playerId, int slot) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT data FROM horse_data WHERE player_uuid = ? AND slot = ?"
        )) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, slot);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return gson.fromJson(rs.getString("data"), HorseData.class);
            }
        } catch (SQLException | JsonSyntaxException e) {
            getLogger().warning("Could not load slot " + slot + " for player " + playerId);
        }
        return null;
    }

    private synchronized void deleteHorseData(UUID playerId, int slot) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM horse_data WHERE player_uuid = ? AND slot = ?"
        )) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to delete horse data", e);
        }
    }

    private synchronized List<Integer> listSlots(UUID playerId) {
        List<Integer> slots = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT slot FROM horse_data WHERE player_uuid = ? ORDER BY slot"
        )) {
            ps.setString(1, playerId.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) slots.add(rs.getInt("slot"));
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Failed to list slots for " + playerId, e);
        }
        return slots;
    }

    // --- Horse distance auto-store ---
    private void checkHorseDistances() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<UUID> horses = playerHorses.get(player.getUniqueId());
            if (horses == null) continue;
            for (UUID horseId : new HashSet<>(horses)) {
                Entity ent = Bukkit.getEntity(horseId);
                if (!(ent instanceof Horse horse)) {
                    horses.remove(horseId);
                    continue;
                }
                if (horse.getLocation().distance(player.getLocation()) > maxDistance) {
                    autoStoreHorse(player, horse);
                }
            }
        }
    }

    private void autoStoreHorse(Player player, Horse horse) {
        int slot = -1;
        for (int i = 1; i <= slotCount; i++) {
            if (loadHorseData(player.getUniqueId(), i) == null) { slot = i; break; }
        }
        if (slot == -1) slot = 1;

        HorseData data = HorseData.fromEntity(horse);
        saveHorseData(player.getUniqueId(), slot, data);
        playerHorses.getOrDefault(player.getUniqueId(), new HashSet<>()).remove(horse.getUniqueId());
        horse.remove();

        player.sendActionBar(Component.text("Horse auto-stored → Slot " + slot, NamedTextColor.GREEN));
    }

    // --- Event handlers ---
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        playerHorses.putIfAbsent(e.getPlayer().getUniqueId(), new HashSet<>());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Set<UUID> horses = playerHorses.get(p.getUniqueId());
        if (horses != null) {
            for (UUID id : new HashSet<>(horses)) {
                Entity ent = Bukkit.getEntity(id);
                if (ent instanceof Horse horse) autoStoreHorse(p, horse);
            }
        }
        playerHorses.remove(p.getUniqueId());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        for (Entity ent : e.getChunk().getEntities()) {
            if (ent instanceof Horse horse && horse.isTamed() && horse.getOwner() != null) {
                UUID owner = horse.getOwner().getUniqueId();
                if (playerHorses.getOrDefault(owner, Collections.emptySet()).contains(horse.getUniqueId())) {
                    Player p = Bukkit.getPlayer(owner);
                    if (p != null && p.isOnline()) autoStoreHorse(p, horse);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        getLogger().log(Level.INFO, e.getView().title().toString());
        if ("Horse Store".equals(e.getView().title().toString())) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        int slot = e.getRawSlot() + 1;
        if (slot < 1 || slot > slotCount) return;

        Material type = clicked.getType();
        if (type == Material.GRAY_DYE) storeHorseSlot(p, slot);
        else if (type == Material.SADDLE) spawnHorseSlot(p, slot);
        openMenu(p);
    }


    // --- GUI actions ---
    private void storeHorseSlot(Player p, int slot) {
        Horse nearest = null;
        for (Entity ent : p.getNearbyEntities(5,5,5)) {
            if (ent instanceof Horse h && h.isTamed() &&
                    p.getUniqueId().equals(Objects.requireNonNull(h.getOwner()).getUniqueId())) {
                nearest = h; break;
            }
        }
        if (nearest == null) {
            p.sendMessage(Component.text("No tamed horse nearby!", NamedTextColor.RED));
            return;
        }
        HorseData data = HorseData.fromEntity(nearest);
        saveHorseData(p.getUniqueId(), slot, data);
        playerHorses.getOrDefault(p.getUniqueId(), new HashSet<>()).remove(nearest.getUniqueId());
        nearest.remove();
        p.sendMessage(Component.text( "Stored → Slot " + slot, NamedTextColor.GREEN));
    }

    private void spawnHorseSlot(Player p, int slot) {
        HorseData hd = loadHorseData(p.getUniqueId(), slot);
        if (hd == null) {
            p.sendMessage(Component.text( "Slot " + slot + " is empty!", NamedTextColor.RED));
            return;
        }
        Location loc = p.getLocation();
        double x = Math.sin(Math.toRadians(loc.getYaw())) * -2;
        double z = Math.cos(Math.toRadians(loc.getYaw())) * 2;
        loc.add(x, 0, z);
        Horse h = (Horse) p.getWorld().spawnEntity(loc, EntityType.HORSE);
        hd.applyToEntity(h);
        h.setOwner(p);
        playerHorses.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(h.getUniqueId());
        deleteHorseData(p.getUniqueId(), slot);
        p.sendMessage(Component.text("Spawned from → Slot " + slot, NamedTextColor.GREEN));
    }

    private void openMenu(Player p) {
        int size = ((slotCount + 8) / 9) * 9;
        Inventory inv = Bukkit.createInventory(null, size, Component.text("Horse Store", NamedTextColor.GREEN));
        List<Integer> slots = listSlots(p.getUniqueId());
        for (int i = 1; i <= slotCount; i++) {
            ItemStack item;
            if (slots.contains(i)) {
                HorseData hd = loadHorseData(p.getUniqueId(), i);
                item = new ItemStack(Material.SADDLE);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(Component.text( "Slot " + i, NamedTextColor.GREEN));
                List<Component> lore = new ArrayList<>();
                assert hd != null;
                lore.add(Component.text(hd.customName != null ? hd.customName : "Unnamed", NamedTextColor.GRAY));
                lore.add(Component.text(hd.color + " " + hd.style, NamedTextColor.GRAY));
                meta.lore(lore);
                meta.lore(lore);
                item.setItemMeta(meta);
            } else {
                item = new ItemStack(Material.GRAY_DYE);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(Component.text( "Empty Slot " + i, NamedTextColor.GRAY));
                item.setItemMeta(meta);
            }
            inv.setItem(i - 1, item);
        }
        p.openInventory(inv);
    }

    // --- Commands & tab completion ---
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;
        if (args.length == 0) openMenu(p);
        else {
            String sub = args[0].toLowerCase();
            if (sub.equals("store") && args.length == 2) {
                try { storeHorseSlot(p, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { p.sendMessage(Component.text( "Usage: /horse store <1-" + slotCount + ">", NamedTextColor.RED)); }
            } else if (sub.equals("spawn") && args.length == 2) {
                try { spawnHorseSlot(p, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { p.sendMessage(Component.text("Usage: /horse spawn <1-" + slotCount + ">", NamedTextColor.RED)); }
            } else if (sub.equals("kill") && args.length == 2) {
                try { deleteHorseData(p.getUniqueId(), Integer.parseInt(args[1]));
                    p.sendMessage(Component.text( "Deleted slot " + args[1], NamedTextColor.RED)); }
                catch (NumberFormatException e) { p.sendMessage(Component.text("Usage: /horse kill <1-" + slotCount + ">", NamedTextColor.RED)); }
            } else if (sub.equals("list")) {
                List<Integer> slots = listSlots(p.getUniqueId());
                if (slots.isEmpty()) p.sendMessage(Component.text( "No horses stored.", NamedTextColor.GRAY));
                else {
                    p.sendMessage(Component.text( "-- Stored Horses --", NamedTextColor.GOLD));
                    for (int s : slots) {
                        HorseData hd = loadHorseData(p.getUniqueId(), s);
                        String name = hd.customName != null ? hd.customName : "Unnamed";
                        p.sendMessage(Component.text( "#" + s + ": " + name, NamedTextColor.GREEN));
                    }
                }
            } else p.sendMessage(Component.text( "Usage: /horse <store|spawn|kill|list>", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("store", "spawn", "kill", "list")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && ("store".equals(args[0]) || "spawn".equals(args[0]) || "kill".equals(args[0]))) {
            return IntStream.rangeClosed(1, slotCount)
                    .mapToObj(Integer::toString)
                    .filter(s -> s.startsWith(args[1]))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // --- HorseData inner class ---
    public static class HorseData {
        public String world;
        public double x, y, z, speed, jump;
        public String color, style, customName, inventory;

        public static HorseData fromEntity(Horse h) {
            HorseData d = new HorseData();
            d.world = h.getWorld().getName();
            Location loc = h.getLocation();
            d.x = loc.getX(); d.y = loc.getY(); d.z = loc.getZ();
            d.speed = Objects.requireNonNull(h.getAttribute(Attribute.MOVEMENT_SPEED)).getBaseValue();
            d.jump = Objects.requireNonNull(h.getAttribute(Attribute.JUMP_STRENGTH)).getBaseValue();
            d.color = h.getColor().name();
            d.style = h.getStyle().name();
            d.customName = h.getCustomName();
            d.inventory = toBase64(h.getInventory().getContents());
            return d;
        }

        public void applyToEntity(Horse h) {
            Objects.requireNonNull(h.getAttribute(Attribute.MOVEMENT_SPEED)).setBaseValue(speed);
            Objects.requireNonNull(h.getAttribute(Attribute.JUMP_STRENGTH)).setBaseValue(jump);
            h.setColor(Horse.Color.valueOf(color));
            h.setStyle(Horse.Style.valueOf(style));
            if (customName != null) {
                h.setCustomName(customName);
                h.setCustomNameVisible(true);
            }
            try {
                ItemStack[] items = fromBase64(inventory);
                h.getInventory().setContents(items);
            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed loading horse inventory: " + e.getMessage());
            }
        }

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

        private static ItemStack[] fromBase64(String data) throws IOException {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (ByteArrayInputStream bin = new ByteArrayInputStream(bytes);
                 BukkitObjectInputStream ois = new BukkitObjectInputStream(bin)) {
                int len = ois.readInt();
                ItemStack[] items = new ItemStack[len];
                for (int i = 0; i < len; i++) items[i] = (ItemStack) ois.readObject();
                return items;
            } catch (ClassNotFoundException e) {
                throw new IOException("Class not found in deserialization", e);
            }
        }
    }
}
