package org.antigravity.autofight.ai;

import org.antigravity.autofight.AutoFightPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

public class TargetTracker {
    private final UUID botUuid;
    private final double aggroRadius;
    private final String bypassPermission;
    private final boolean targetCreative;
    private final boolean targetSpectator;

    private Player currentTarget;
    private Vector targetVelocity = new Vector(0, 0, 0);
    private Location lastTargetLocation;

    public TargetTracker(UUID botUuid, double aggroRadius, String bypassPermission, boolean targetCreative, boolean targetSpectator) {
        this.botUuid = botUuid;
        this.aggroRadius = aggroRadius;
        this.bypassPermission = bypassPermission;
        this.targetCreative = targetCreative;
        this.targetSpectator = targetSpectator;
    }

    public Player updateTarget(Location botLocation) {
        // If current target is still valid and in range, maintain focus unless targeting another bot when human is present
        boolean currentIsBot = currentTarget != null && AutoFightPlugin.getInstance().getActiveBots().containsKey(currentTarget.getUniqueId());
        if (isValidTarget(currentTarget, botLocation) && !currentIsBot) {
            updateTargetVelocity();
            return currentTarget;
        }

        // Search for nearest valid player, prioritizing human players over other bots
        Player closestHuman = null;
        double closestHumanDistSq = aggroRadius * aggroRadius;
        Player closestBot = null;
        double closestBotDistSq = aggroRadius * aggroRadius;

        for (Player player : botLocation.getWorld().getPlayers()) {
            if (!isValidTarget(player, botLocation)) {
                continue;
            }

            double distSq = player.getLocation().distanceSquared(botLocation);
            boolean isBot = AutoFightPlugin.getInstance().getActiveBots().containsKey(player.getUniqueId());

            if (!isBot) {
                if (distSq < closestHumanDistSq) {
                    closestHuman = player;
                    closestHumanDistSq = distSq;
                }
            } else {
                if (distSq < closestBotDistSq) {
                    closestBot = player;
                    closestBotDistSq = distSq;
                }
            }
        }

        Player chosen = (closestHuman != null) ? closestHuman : closestBot;
        if (chosen != null) {
            this.currentTarget = chosen;
            this.lastTargetLocation = currentTarget.getLocation().clone();
            this.targetVelocity = new Vector(0, 0, 0);
        } else {
            this.currentTarget = null;
            this.lastTargetLocation = null;
            this.targetVelocity = new Vector(0, 0, 0);
        }
        return currentTarget;
    }

    private void updateTargetVelocity() {
        if (currentTarget != null && lastTargetLocation != null && currentTarget.getWorld().equals(lastTargetLocation.getWorld())) {
            Location currLoc = currentTarget.getLocation();
            this.targetVelocity = currLoc.toVector().subtract(lastTargetLocation.toVector());
            this.lastTargetLocation = currLoc.clone();
        } else if (currentTarget != null) {
            this.lastTargetLocation = currentTarget.getLocation().clone();
            this.targetVelocity = new Vector(0, 0, 0);
        }
    }

    public boolean isValidTarget(Player player, Location botLocation) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return false;
        }
        if (player.getUniqueId().equals(botUuid)) {
            return false;
        }
        if (!player.getWorld().equals(botLocation.getWorld())) {
            return false;
        }
        if (player.getLocation().distanceSquared(botLocation) > aggroRadius * aggroRadius) {
            return false;
        }
        if (!targetCreative && player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        if (!targetSpectator && player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (bypassPermission != null && !bypassPermission.isEmpty() && player.hasPermission(bypassPermission)) {
            return false;
        }
        return true;
    }

    public Player getCurrentTarget() {
        return currentTarget;
    }

    public Vector getTargetVelocity() {
        return targetVelocity;
    }
}
