package org.antigravity.autofight;

import org.antigravity.autofight.command.AutoFightCommand;
import org.antigravity.autofight.config.DifficultyProfile;
import org.antigravity.autofight.config.KitManager;
import org.antigravity.autofight.entity.AutoFightPlayer;
import org.antigravity.autofight.entity.SkinManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AutoFightPlugin extends JavaPlugin {
    private static AutoFightPlugin instance;

    private KitManager kitManager;
    private SkinManager skinManager;
    private final Map<String, DifficultyProfile> difficultyProfiles = new HashMap<>();
    private final Map<UUID, AutoFightPlayer> activeBots = new ConcurrentHashMap<>();

    private double aggroRadius;
    private boolean targetCreative;
    private boolean targetSpectator;
    private String bypassPermission;
    private String defaultDifficulty;
    private String defaultMode;
    private String defaultKit;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.kitManager = new KitManager(getLogger());
        this.skinManager = new SkinManager(getLogger());

        loadSettings();

        // Register Command via CommandMap
        Bukkit.getCommandMap().register("autofight", new AutoFightCommand(this));

        // Register Combat Event Listener
        Bukkit.getPluginManager().registerEvents(new org.antigravity.autofight.listener.CombatListener(this), this);

        getLogger().info("AutoFightBot plugin enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Despawn all active bots cleanly
        for (AutoFightPlayer bot : activeBots.values()) {
            try {
                bot.despawn();
            } catch (Exception e) {
                getLogger().warning("Error despawning bot " + bot.getBotName() + ": " + e.getMessage());
            }
        }
        activeBots.clear();
        getLogger().info("AutoFightBot plugin disabled.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        loadSettings();
    }

    private void loadSettings() {
        kitManager.loadKits(getConfig());

        ConfigurationSection settings = getConfig().getConfigurationSection("settings");
        if (settings != null) {
            this.aggroRadius = settings.getDouble("aggro-radius", 24.0);
            this.targetCreative = settings.getBoolean("target-creative", false);
            this.targetSpectator = settings.getBoolean("target-spectator", false);
            this.bypassPermission = settings.getString("bypass-permission", "autofight.bypass");
            this.defaultDifficulty = settings.getString("default-difficulty", "HARD").toUpperCase();
            this.defaultMode = settings.getString("default-mode", "HUMANIZED").toUpperCase();
            this.defaultKit = settings.getString("default-kit", "diamond_pvp");
        }

        // Load difficulty profiles
        difficultyProfiles.clear();
        ConfigurationSection diffSec = getConfig().getConfigurationSection("difficulties");
        if (diffSec != null) {
            for (String key : diffSec.getKeys(false)) {
                difficultyProfiles.put(key.toUpperCase(), DifficultyProfile.fromConfig(key, diffSec.getConfigurationSection(key)));
            }
        }
    }

    public void registerBot(AutoFightPlayer bot) {
        activeBots.put(bot.getUUID(), bot);
    }

    public void unregisterBot(UUID uuid) {
        activeBots.remove(uuid);
    }

    public void clearActiveBots() {
        activeBots.clear();
    }

    public Map<UUID, AutoFightPlayer> getActiveBots() {
        return Collections.unmodifiableMap(activeBots);
    }

    public DifficultyProfile getDifficulty(String name) {
        if (name == null) return difficultyProfiles.getOrDefault("HARD", DifficultyProfile.defaultHard());
        return difficultyProfiles.getOrDefault(name.toUpperCase(), difficultyProfiles.getOrDefault("HARD", DifficultyProfile.defaultHard()));
    }

    public static AutoFightPlugin getInstance() {
        return instance;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public SkinManager getSkinManager() {
        return skinManager;
    }

    public double getAggroRadius() {
        return aggroRadius;
    }

    public boolean isTargetCreative() {
        return targetCreative;
    }

    public boolean isTargetSpectator() {
        return targetSpectator;
    }

    public String getBypassPermission() {
        return bypassPermission;
    }

    public String getDefaultDifficulty() {
        return defaultDifficulty;
    }

    public String getDefaultMode() {
        return defaultMode;
    }

    public String getDefaultKit() {
        return defaultKit;
    }
}
