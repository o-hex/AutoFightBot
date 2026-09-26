package org.antigravity.autofight.ai;

import org.antigravity.autofight.config.DifficultyProfile;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

public class HeuristicEngine {
    private final DifficultyProfile profile;
    private final boolean isHumanized;
    private final Random random = new Random();

    // Reaction buffer: holds snapshots of target state
    public record TargetSnapshot(Location location, boolean isBlocking, boolean isSprinting, long tick) {}
    private final Deque<TargetSnapshot> reactionQueue = new ArrayDeque<>();

    // Strafe state
    private float currentStrafe = 0.0f; // -1.0 left, +1.0 right
    private int strafeTicksRemaining = 0;

    public HeuristicEngine(DifficultyProfile profile, boolean isHumanized) {
        this.profile = profile;
        this.isHumanized = isHumanized;
    }

    public void recordTargetState(Player target, long currentTick) {
        if (target == null) {
            reactionQueue.clear();
            return;
        }

        reactionQueue.addLast(new TargetSnapshot(
                target.getLocation().clone(),
                target.isBlocking(),
                target.isSprinting(),
                currentTick
        ));

        // Maintain queue size according to reaction delay
        int maxDelay = Math.max(1, profile.reactionDelayTicks());
        while (reactionQueue.size() > maxDelay + 2) {
            reactionQueue.removeFirst();
        }
    }

    public TargetSnapshot getPerceivedTargetState(long currentTick) {
        if (!isHumanized || profile.reactionDelayTicks() <= 0 || reactionQueue.isEmpty()) {
            return reactionQueue.peekLast();
        }

        long targetTick = currentTick - profile.reactionDelayTicks();
        TargetSnapshot best = reactionQueue.peekFirst();
        for (TargetSnapshot snapshot : reactionQueue) {
            if (snapshot.tick() <= targetTick) {
                best = snapshot;
            } else {
                break;
            }
        }
        return best;
    }

    public Vector calculateRotation(float currentYaw, float currentPitch, Location botEyeLoc, Location targetLoc, Vector targetVel) {
        // Predictive lead: slight lead in the direction of velocity
        double leadFactor = isHumanized ? 1.5 : 1.0;
        Location aimAt = targetLoc.clone().add(0, 1.2, 0); // Aim at upper chest/neck
        if (targetVel != null) {
            aimAt.add(targetVel.clone().multiply(leadFactor));
        }

        Vector dir = aimAt.toVector().subtract(botEyeLoc.toVector());
        double dx = dir.getX();
        double dy = dir.getY();
        double dz = dir.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // If target is intersecting/overlapping (< 0.45m), preserve current yaw to prevent spinning/whipping
        if (horizontalDist < 0.45) {
            return new Vector(currentYaw, currentPitch, 0);
        }

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        if (!isHumanized || profile.aimSmoothingDegPerTick() >= 180.0) {
            return new Vector(targetYaw, targetPitch, 0);
        }

        // Smooth rotation using maximum degrees per tick
        float maxStep = (float) profile.aimSmoothingDegPerTick();

        float deltaYaw = wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;

        float clampedDeltaYaw = Math.max(-maxStep, Math.min(maxStep, deltaYaw));
        float clampedDeltaPitch = Math.max(-maxStep, Math.min(maxStep, deltaPitch));

        // Add subtle human jitter
        float jitterYaw = (float) ((random.nextGaussian() * 0.4));
        float jitterPitch = (float) ((random.nextGaussian() * 0.2));

        float finalYaw = currentYaw + clampedDeltaYaw + jitterYaw;
        float finalPitch = Math.max(-90.0f, Math.min(90.0f, currentPitch + clampedDeltaPitch + jitterPitch));

        return new Vector(finalYaw, finalPitch, 0);
    }

    public float getDesiredStrafe(double distanceToTarget) {
        if (strafeTicksRemaining <= 0) {
            strafeTicksRemaining = 8 + random.nextInt(12);
            if (random.nextDouble() < 0.85) {
                // Flip strafe direction or pick left/right
                currentStrafe = (currentStrafe == 0.0f) ? (random.nextBoolean() ? 1.0f : -1.0f) : -currentStrafe;
            } else {
                currentStrafe = 0.0f;
            }
        } else {
            strafeTicksRemaining--;
        }

        // Long-range approach (> 6.0m): direct charge
        if (distanceToTarget > 6.0) {
            return 0.0f;
        }
        // Medium-range approach (3.5m - 6.0m): slight zig-zag juking
        if (distanceToTarget > 3.5) {
            return currentStrafe * 0.50f;
        }
        // Point-blank range (< 0.9m): circle out to reset spacing
        if (distanceToTarget < 0.9) {
            return currentStrafe * 0.60f;
        }
        // Active combat spacing (0.9m - 3.5m): snappy, full-speed strafe (A/D movement)
        return currentStrafe * 0.90f;
    }

    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }
}
