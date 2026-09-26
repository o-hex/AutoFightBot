package org.antigravity.autofight.listener;

import org.antigravity.autofight.AutoFightPlugin;
import org.antigravity.autofight.entity.AutoFightPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

public class CombatListener implements Listener {
    private final AutoFightPlugin plugin;

    public CombatListener(AutoFightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        AutoFightPlayer bot = plugin.getActiveBots().get(victim.getUniqueId());
        if (bot == null) {
            return;
        }

        Entity damager = event.getDamager();

        // 1. If victim is a bot and blocking with shield:
        if (victim.isBlocking()) {
            if (damager instanceof Player attacker) {
                if (attacker.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) {
                    // Axe disables bot's shield for 5 seconds (100 ticks)
                    bot.disableShield(100);
                }
            }
            return;
        }

        // 2. If damager is a bot attacking a blocking player with an axe:
        if (damager instanceof Player attacker) {
            AutoFightPlayer attackingBot = plugin.getActiveBots().get(attacker.getUniqueId());
            if (attackingBot != null && victim.isBlocking()) {
                if (attacker.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) {
                    victim.setCooldown(org.bukkit.Material.SHIELD, 100);
                    victim.getWorld().playSound(victim.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f);
                    attackingBot.getCombatMachine().onTargetShieldBroken();
                }
            }
        }
        boolean isSprintHit = false;
        Vector dir;

        if (damager instanceof Player attacker) {
            isSprintHit = attacker.isSprinting();
            dir = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        } else {
            dir = victim.getLocation().toVector().subtract(damager.getLocation().toVector()).setY(0);
        }

        if (dir.lengthSquared() > 0.001) {
            dir.normalize();
        } else {
            dir = damager.getLocation().getDirection().setY(0).normalize();
        }

        bot.onDamagedBy(damager, dir, isSprintHit);
    }
}
