package com.supaspawn;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class SupaSpawn extends JavaPlugin implements Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern HEX_TAG = Pattern.compile("<##([A-Fa-f0-9]{6})>");
    private final Map<UUID, BukkitRunnable> pendingTeleports = new HashMap<>();
    private final Map<UUID, Location> startLocations = new HashMap<>();
    private Location spawnLocation;
    private static final String SPAWN_GUI_ID = "supaspawn:main";

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSpawn();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("spawn")).setTabCompleter((sender, command, alias, args) -> List.of());
        getLogger().info("SupaSpawn 1.0.0 enabled.");
    }

    @Override public void onDisable() {
        pendingTeleports.values().forEach(BukkitRunnable::cancel);
        pendingTeleports.clear();
        startLocations.clear();
    }

    private void loadSpawn() {
        String worldName = getConfig().getString("spawn.world", "");
        if (worldName.isBlank()) { spawnLocation = null; return; }
        World world = Bukkit.getWorld(worldName);
        if (world == null) { spawnLocation = null; getLogger().warning("Saved spawn world '" + worldName + "' is not loaded."); return; }
        spawnLocation = new Location(world, getConfig().getDouble("spawn.x"), getConfig().getDouble("spawn.y"),
            getConfig().getDouble("spawn.z"), (float)getConfig().getDouble("spawn.yaw"), (float)getConfig().getDouble("spawn.pitch"));
    }

    private void saveSpawn(Location location) {
        spawnLocation = location.clone();
        getConfig().set("spawn.world", location.getWorld().getName());
        getConfig().set("spawn.x", location.getX()); getConfig().set("spawn.y", location.getY());
        getConfig().set("spawn.z", location.getZ()); getConfig().set("spawn.yaw", location.getYaw());
        getConfig().set("spawn.pitch", location.getPitch());
        saveConfig();
    }

    private Component message(String raw) {
        if (raw == null) return Component.empty();
        Matcher matcher = HEX_TAG.matcher(raw);
        StringBuffer converted = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(converted, "<#" + matcher.group(1) + ">");
        matcher.appendTail(converted);
        try { return MINI.deserialize(converted.toString()); }
        catch (RuntimeException ignored) {
            return LegacyComponentSerializer.legacySection().deserialize(convertLegacyHex(raw));
        }
    }

    private String convertLegacyHex(String input) {
        Matcher matcher = HEX_TAG.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            StringBuilder legacy = new StringBuilder("§x");
            for (char c : matcher.group(1).toCharArray()) legacy.append('§').append(c);
            matcher.appendReplacement(result, Matcher.quoteReplacement(legacy.toString()));
        }
        matcher.appendTail(result);
        return result.toString().replace('&', '§');
    }

    private void tell(Player player, String key) {
        player.sendMessage(message(getConfig().getString("messages." + key, "")));
    }

    private void actionBar(Player player, Component text) { player.sendActionBar(text); }

    private void playConfiguredSound(Player player, String path) {
        String name = getConfig().getString(path, "");
        if (name == null || name.isBlank()) return;
        try { player.playSound(player.getLocation(), Sound.valueOf(name.toUpperCase(Locale.ROOT)), 1f, 1f); }
        catch (IllegalArgumentException ex) { getLogger().warning("Unknown sound in config at " + path + ": " + name); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("setspawn") || name.equals("delspawn")) {
            String permission = name.equals("setspawn") ? "supaspawn.setspawn" : "supaspawn.delspawn";
            if (!sender.hasPermission(permission)) { sender.sendMessage(message(getConfig().getString("messages.no-permission"))); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used by a player."); return true; }
            if (name.equals("setspawn")) { saveSpawn(player.getLocation()); tell(player, "set-success"); }
            else {
                spawnLocation = null;
                for (String key : List.of("spawn.world", "spawn.x", "spawn.y", "spawn.z", "spawn.yaw", "spawn.pitch")) getConfig().set(key, null);
                saveConfig(); tell(player, "delete-success");
            }
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used by a player."); return true; }
        if (args.length > 0) {
            player.sendMessage(message("<red>Usage: /spawn</red>"));
            return true;
        }
        if (getConfig().getBoolean("gui.enabled", true)) openSpawnGui(player);
        else beginTeleport(player);
        return true;
    }

    private void openSpawnGui(Player player) {
        int rows = 3;
        String title = getConfig().getString("gui.title", "<#37BFF8>Spawn");
        Inventory inventory = Bukkit.createInventory(null, rows * 9, message(title));
        Material filler = parseMaterial(getConfig().getString("gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack fillerItem = makeGuiItem(filler, getConfig().getString("gui.filler-name", " "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, fillerItem.clone());
        int slot = getConfig().getInt("gui.spawn-slot", 13);
        if (slot < 0 || slot >= inventory.getSize()) slot = 13;
        List<String> lore = getConfig().getStringList("gui.spawn-lore");
        inventory.setItem(slot, makeGuiItem(parseMaterial(getConfig().getString("gui.spawn-material", "COMPASS"), Material.COMPASS),
            getConfig().getString("gui.spawn-name", "<#37BFF8>Spawn"), lore));
        player.openInventory(inventory);
    }

    private ItemStack makeGuiItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(message(name));
            if (lore != null && !lore.isEmpty()) meta.lore(lore.stream().map(this::message).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String name = raw.trim().toUpperCase(Locale.ROOT);
        if (name.startsWith("MINECRAFT:")) name = name.substring("MINECRAFT:".length());
        try { return Material.valueOf(name); }
        catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid GUI material '" + raw + "'; using " + fallback.name() + ".");
            return fallback;
        }
    }

    @EventHandler public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().title().equals(message(getConfig().getString("gui.title", "<#37BFF8>Spawn")))) return;
        event.setCancelled(true);
        if (event.getRawSlot() != getConfig().getInt("gui.spawn-slot", 13)) return;
        player.closeInventory();
        beginTeleport(player);
    }

    @EventHandler public void onGuiDrag(InventoryDragEvent event) {
        if (event.getView().title().equals(message(getConfig().getString("gui.title", "<#37BFF8>Spawn")))) event.setCancelled(true);
    }

    private void beginTeleport(Player player) {
        if (spawnLocation == null || spawnLocation.getWorld() == null) { tell(player, "no-spawn"); return; }
        if (getConfig().getBoolean("teleport.already-at-spawn-check-world", true)
            && player.getWorld().getName().equalsIgnoreCase(getConfig().getString("teleport.spawn-world-name", "spawn"))) {
            Component text = message(getConfig().getString("messages.already-at-spawn"));
            player.sendMessage(text); actionBar(player, text); playConfiguredSound(player, "teleport.sound-failure"); return;
        }
        UUID id = player.getUniqueId();
        if (pendingTeleports.containsKey(id)) return;
        int seconds = Math.max(0, getConfig().getInt("teleport.countdown-seconds", 5));
        startLocations.put(id, player.getLocation().clone());
        BukkitRunnable task = new BukkitRunnable() {
            int remaining = seconds;
            @Override public void run() {
                if (!player.isOnline() || !pendingTeleports.containsKey(id)) { cancelTeleport(id); cancel(); return; }
                if (remaining <= 0) {
                    Location destination = spawnLocation;
                    pendingTeleports.remove(id); startLocations.remove(id);
                    if (destination == null || destination.getWorld() == null) { tell(player, "teleport-failed"); cancel(); return; }
                    player.teleportAsync(destination.clone()).thenAccept(success -> Bukkit.getScheduler().runTask(SupaSpawn.this, () -> {
                        if (success && player.isOnline()) {
                            Component text = message(getConfig().getString("messages.teleported"));
                            actionBar(player, text); player.sendMessage(text); playConfiguredSound(player, "teleport.sound-success");
                        } else if (player.isOnline()) tell(player, "teleport-failed");
                    }));
                    cancel(); return;
                }
                String raw = getConfig().getString("messages.countdown", "").replace("{seconds}", Integer.toString(remaining));
                Component text = message(raw); player.sendMessage(text); actionBar(player, text);
                remaining--;
            }
        };
        pendingTeleports.put(id, task);
        task.runTaskTimer(this, 0L, 20L);
    }

    private void cancelTeleport(UUID id) {
        BukkitRunnable task = pendingTeleports.remove(id);
        if (task != null) task.cancel();
        startLocations.remove(id);
    }

    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("teleport.cancel-on-move", true) || event.getTo() == null) return;
        Player player = event.getPlayer(); UUID id = player.getUniqueId();
        if (!pendingTeleports.containsKey(id)) return;
        Location start = startLocations.get(id);
        if (start == null) return;
        Location to = event.getTo();
        double threshold = Math.max(0.0, getConfig().getDouble("teleport.movement-threshold", 0.5));
        if (start.getWorld() != to.getWorld() || start.distance(to) > threshold) {
            cancelTeleport(id);
            Component text = message(getConfig().getString("messages.cancelled"));
            player.sendMessage(text); actionBar(player, text);
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (getConfig().getBoolean("join.hide-join-message", true)) event.joinMessage(null);
        if (!player.hasPlayedBefore()) {
            if (spawnLocation != null) player.teleportAsync(spawnLocation.clone());
            for (String line : getConfig().getStringList("first-join.welcome-messages")) player.sendMessage(message(line));
            for (String entry : getConfig().getStringList("first-join.starter-kit")) {
                String[] parts = entry.split(":", 2);
                try {
                    Material material = Material.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
                    int amount = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
                    if (material.isItem() && amount > 0) {
                        ItemStack item = new ItemStack(material, Math.min(amount, material.getMaxStackSize()));
                        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                        overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
                    }
                } catch (IllegalArgumentException ex) { getLogger().warning("Invalid first-join starter-kit entry: " + entry); }
            }
            for (String consoleCommand : getConfig().getStringList("first-join.console-commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCommand.replace("{player}", player.getName()));
            }
        }
        if (getConfig().getBoolean("join.teleport-to-spawn-when-joining-spawn-world", true)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline() && spawnLocation != null
                    && player.getWorld().getName().equalsIgnoreCase(getConfig().getString("teleport.spawn-world-name", "spawn"))) {
                    player.teleportAsync(spawnLocation.clone());
                }
            });
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (getConfig().getBoolean("join.hide-leave-message", true)) event.quitMessage(null);
        cancelTeleport(event.getPlayer().getUniqueId());
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        if (getConfig().getBoolean("join.teleport-on-respawn", true) && spawnLocation != null) {
            event.setRespawnLocation(spawnLocation.clone());
        }
    }
}
