package org.antigravity.autofight.config;

import org.bukkit.configuration.ConfigurationSection;

public record DifficultyProfile(
        String name,
        int reactionDelayTicks,
        double critChance,
        double wTapChance,
        double shieldBlockChance,
        double aimSmoothingDegPerTick,
        double attackChargeThreshold
) {
    public static DifficultyProfile fromConfig(String name, ConfigurationSection section) {
        if (section == null) {
            return defaultHard();
        }
        return new DifficultyProfile(
                name.toUpperCase(),
                section.getInt("reaction-delay-ticks", 3),
                section.getDouble("crit-chance", 0.85),
                section.getDouble("w-tap-chance", 0.90),
                section.getDouble("shield-block-chance", 0.90),
                section.getDouble("aim-smoothing-deg-per-tick", 40.0),
                section.getDouble("attack-charge-threshold", 0.98)
        );
    }

    public static DifficultyProfile defaultHard() {
        return new DifficultyProfile("HARD", 3, 0.85, 0.90, 0.90, 40.0, 0.98);
    }
}
