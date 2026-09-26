package org.antigravity.autofight.command;

import org.antigravity.autofight.AutoFightPlugin;
import org.antigravity.autofight.config.DifficultyProfile;
import org.antigravity.autofight.config.KitManager;
import org.antigravity.autofight.entity.AutoFightPlayer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AutoFightCommand extends Command {
    private final AutoFightPlugin plugin;

    public AutoFightCommand(AutoFightPlugin plugin) {
        super("autofight");
        this.plugin = plugin;
        this.setDescription("Main command for AutoFightBot");
        this.setUsage("/autofight <spawn|kill|list|reload|kits>");
        this.setPermission("autofight.admin");
        this.setAliases(List.of("afb", "af"));
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("autofight.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "spawn" -> handleSpawn(sender, args);
            case "kill" -> handleKill(sender, args);
            case "list" -> handleList(sender);
            case "reload" -> handleReload(sender);
            case "kits" -> handleKits(sender);
            default -> sendHelp(sender);
        }
        return true;
    }
    private void handleSpawn(CommandSender sender, String[] args) {
        Location spawnLoc;
        if (sender instanceof Player player) {
            spawnLoc = player.getLocation();
        } else if (!plugin.getServer().getOnlinePlayers().isEmpty()) {
            spawnLoc = plugin.getServer().getOnlinePlayers().iterator().next().getLocation();
        } else if (!plugin.getServer().getWorlds().isEmpty()) {
            spawnLoc = plugin.getServer().getWorlds().get(0).getSpawnLocation();
        } else {
            sender.sendMessage(ChatColor.RED + "No valid world found to spawn bot.");
            return;
        }

        String botName = args.length > 1 ? args[1] : "HostileBot_" + (int)(Math.random() * 1000);
        String kitName = args.length > 2 ? args[2] : plugin.getDefaultKit();
        String diffName = args.length > 3 ? args[3] : plugin.getDefaultDifficulty();
        String modeName = args.length > 4 ? args[4] : plugin.getDefaultMode();

        KitManager.Kit kit = plugin.getKitManager().getKit(kitName);
        if (kit == null) {
            sender.sendMessage(ChatColor.RED + "Unknown kit: " + kitName + ". Use /autofight kits to see available kits.");
            return;
        }

        DifficultyProfile profile = plugin.getDifficulty(diffName);
        boolean isHumanized = !modeName.equalsIgnoreCase("ROBOTIC");

        sender.sendMessage(ChatColor.YELLOW + "Spawning violent bot '" + botName + "' [Kit: " + kitName + ", Diff: " + diffName + ", Mode: " + (isHumanized ? "Humanized" : "Robotic") + "] at " + spawnLoc.getWorld().getName() + "...");

        // Fetch skin asynchronously and spawn
        plugin.getSkinManager().fetchSkin(botName).thenAccept(skinProperty -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    AutoFightPlayer bot = AutoFightPlayer.spawn(plugin, spawnLoc, botName, skinProperty, kit, profile, isHumanized);
                    plugin.registerBot(bot);
                    sender.sendMessage(ChatColor.GREEN + "AutoFight bot " + botName + " spawned successfully! Beware, it is hostile!");
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "Failed to spawn AutoFight bot: " + e.getMessage());
                    plugin.getLogger().severe("Spawn error: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });
    }

    private void handleKill(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
            List<AutoFightPlayer> bots = new ArrayList<>(plugin.getActiveBots().values());
            int count = bots.size();
            for (AutoFightPlayer bot : bots) {
                bot.despawn();
            }
            plugin.clearActiveBots();
            sender.sendMessage(ChatColor.GREEN + "Despawned " + count + " active AutoFight bot(s).");
            return;
        }

        String targetName = args[1];
        AutoFightPlayer found = null;
        for (AutoFightPlayer bot : new ArrayList<>(plugin.getActiveBots().values())) {
            if (bot.getBotName().equalsIgnoreCase(targetName) || bot.getUUID().toString().equalsIgnoreCase(targetName)) {
                found = bot;
                break;
            }
        }

        if (found != null) {
            found.despawn();
            sender.sendMessage(ChatColor.GREEN + "Despawned bot " + found.getBotName() + ".");
        } else {
            sender.sendMessage(ChatColor.RED + "No active bot found with name or UUID: " + targetName);
        }
    }

    private void handleList(CommandSender sender) {
        if (plugin.getActiveBots().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No AutoFight bots currently active.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== Active AutoFight Bots (" + plugin.getActiveBots().size() + ") ===");
        for (AutoFightPlayer bot : plugin.getActiveBots().values()) {
            Location loc = bot.getBukkitPlayer().getLocation();
            sender.sendMessage(ChatColor.AQUA + "- " + bot.getBotName() + ChatColor.GRAY + " at [" +
                    loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "]" +
                    " | HP: " + String.format("%.1f", bot.getBukkitPlayer().getHealth()));
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadPluginConfig();
        sender.sendMessage(ChatColor.GREEN + "AutoFightBot config reloaded successfully!");
    }

    private void handleKits(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Available Kits ===");
        sender.sendMessage(ChatColor.YELLOW + "diamond_pvp, netherite_pvp, iron_berserk");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== AutoFightBot Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/autofight spawn <name> [kit] [difficulty] [humanized|robotic]" + ChatColor.GRAY + " - Spawn a bot");
        sender.sendMessage(ChatColor.YELLOW + "/autofight kill <all|name>" + ChatColor.GRAY + " - Despawn bots");
        sender.sendMessage(ChatColor.YELLOW + "/autofight list" + ChatColor.GRAY + " - List all active bots");
        sender.sendMessage(ChatColor.YELLOW + "/autofight kits" + ChatColor.GRAY + " - View available kits");
        sender.sendMessage(ChatColor.YELLOW + "/autofight reload" + ChatColor.GRAY + " - Reload configuration");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.addAll(Arrays.asList("spawn", "kill", "list", "kits", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("kill")) {
            list.add("all");
            for (AutoFightPlayer bot : plugin.getActiveBots().values()) {
                list.add(bot.getBotName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("spawn")) {
            list.addAll(Arrays.asList("diamond_pvp", "netherite_pvp", "iron_berserk"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("spawn")) {
            list.addAll(Arrays.asList("EASY", "MEDIUM", "HARD", "NIGHTMARE"));
        } else if (args.length == 5 && args[0].equalsIgnoreCase("spawn")) {
            list.addAll(Arrays.asList("HUMANIZED", "ROBOTIC"));
        }
        return list;
    }
}
