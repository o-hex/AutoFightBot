package org.antigravity.autofight.ai;

import org.antigravity.autofight.config.DifficultyProfile;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Random;

public class CombatStateMachine {
    public enum CombatState {
        CHASING,
        CRIT_JUMPING,
        W_TAP_RESET,
        BLOCKING,
        SHIELD_BREAK_SWAP
    }

    private final DifficultyProfile profile;
    private final Random random = new Random();

    private CombatState currentState = CombatState.CHASING;
    private int stateTicks = 0;
    private int previousSwordSlot = 0;

    // Internal robust attack & shield timers (independent of client packets)
    private int ticksSinceLastAttack = 20;
    private int shieldBlockTicksRemaining = 0;
    private int ticksSinceLastJumpCrit = 30;
    private boolean needSwordSwap = false;

    public CombatStateMachine(DifficultyProfile profile) {
        this.profile = profile;
    }

    public CombatAction updateCombat(
            Player bot,
            Player target,
            double horizontalDistance,
            double eyeDistance,
            boolean isFalling,
            boolean isOnGround,
            float attackCooldownCharge
    ) {
        stateTicks++;
        ticksSinceLastAttack++;
        ticksSinceLastJumpCrit++;

        // 1. Weapon speed cooldown calculation
        int cooldownTicks = 12; // default Diamond/Netherite Sword (1.6 speed -> 12.5 ticks)
        ItemStack mainHand = bot.getInventory().getItemInMainHand();
        if (mainHand != null && mainHand.getType().name().endsWith("_AXE")) {
            cooldownTicks = 20; // Axe (1.0 speed -> 20 ticks)
        }
        int readyTicks = (int) Math.max(4, cooldownTicks * profile.attackChargeThreshold());
        boolean cooldownReady = ticksSinceLastAttack >= readyTicks;

        // 2. Shield break / Axe swap & Weapon management
        int switchSlot = -1;
        boolean holdingAxe = mainHand != null && mainHand.getType().name().endsWith("_AXE");

        if (target.isBlocking() && horizontalDistance <= 3.2) {
            int axeSlot = findAxeSlot(bot.getInventory());
            if (axeSlot != -1 && bot.getInventory().getHeldItemSlot() != axeSlot) {
                switchSlot = axeSlot;
            }
        } else if (holdingAxe && (!target.isBlocking() || needSwordSwap)) {
            // Target is NOT blocking (or shield was just disabled) -> SWITCH BACK TO SWORD IMMEDIATELY!
            needSwordSwap = false;
            int swordSlot = findSwordSlot(bot.getInventory());
            if (swordSlot != -1 && bot.getInventory().getHeldItemSlot() != swordSlot) {
                switchSlot = swordSlot;
            }
        }

        // 3. Shield Defense:
        // Hold shield defensively while weapon is cooling down and close to target
        boolean shouldBlock = false;
        if (!cooldownReady && horizontalDistance <= 3.0 && !target.isBlocking() && currentState != CombatState.CRIT_JUMPING) {
            if (shieldBlockTicksRemaining <= 0) {
                if (random.nextDouble() < profile.shieldBlockChance() * 0.35) {
                    shieldBlockTicksRemaining = 10 + random.nextInt(12);
                }
            } else {
                shieldBlockTicksRemaining--;
                shouldBlock = true;
            }
        } else if (cooldownReady && horizontalDistance <= 3.2) {
            // Drop shield immediately so attack can land cleanly
            shieldBlockTicksRemaining = 0;
            shouldBlock = false;
        }

        // 4. Melee Attack & Crit Execution (Strict Vanilla Survival Reach <= 2.95m / 3.15m eye)
        boolean shouldAttack = false;
        boolean shouldJump = false;
        boolean shouldSprint = true;
        float forwardInput = 1.0f;
        boolean inMeleeReach = horizontalDistance <= 2.95 && eyeDistance <= 3.15;

        if (currentState == CombatState.CRIT_JUMPING) {
            shouldSprint = false; // Sprint cancels vanilla critical hits!
            forwardInput = 1.0f;

            if (stateTicks == 1) {
                // Launch jump towards target
                shouldJump = true;
            }

            // Downward descent: strike when falling or descending after jump apex
            boolean descending = isFalling || stateTicks >= 4;
            boolean critCooldownReady = ticksSinceLastAttack >= Math.max(5, (int) (readyTicks * 0.75));

            if (descending && critCooldownReady && inMeleeReach) {
                shouldAttack = true;
                ticksSinceLastAttack = 0;
                ticksSinceLastJumpCrit = 0;
                shieldBlockTicksRemaining = 0;

                if (holdingAxe) {
                    needSwordSwap = true;
                }

                if (random.nextDouble() < profile.wTapChance()) {
                    currentState = CombatState.W_TAP_RESET;
                    stateTicks = 0;
                } else {
                    currentState = CombatState.CHASING;
                    stateTicks = 0;
                }
            } else if (isOnGround && stateTicks >= 4) {
                // Landed back on ground without connecting crit
                currentState = CombatState.CHASING;
                stateTicks = 0;
            }
        } else if (inMeleeReach) {
            if (cooldownReady) {
                // Neutral jump-crit initiation check
                if (isOnGround && ticksSinceLastJumpCrit >= 14 && horizontalDistance >= 0.8 && horizontalDistance <= 2.8
                        && random.nextDouble() < profile.critChance() * 0.45) {
                    currentState = CombatState.CRIT_JUMPING;
                    shouldJump = true;
                    shouldSprint = false;
                    forwardInput = 1.0f;
                    stateTicks = 1;
                    ticksSinceLastJumpCrit = 0;
                } else {
                    // SPRINT HIT (Opener): connect attack while sprinting to deal vanilla sprint knockback!
                    shouldAttack = true;
                    shouldSprint = true;
                    forwardInput = 1.0f;
                    ticksSinceLastAttack = 0;
                    shieldBlockTicksRemaining = 0;

                    if (holdingAxe) {
                        needSwordSwap = true;
                    }

                    // KNOCKBACK-TO-CRIT COMBO LOOP:
                    if (random.nextDouble() < profile.critChance()) {
                        currentState = CombatState.CRIT_JUMPING;
                        stateTicks = 0; // next tick will be tick 1 (trigger jump)
                    } else if (random.nextDouble() < profile.wTapChance()) {
                        currentState = CombatState.W_TAP_RESET;
                        stateTicks = 0;
                    }
                }
            }
        } else {
            // Beyond close combat reach
            if (currentState != CombatState.W_TAP_RESET && currentState != CombatState.CRIT_JUMPING) {
                currentState = CombatState.CHASING;
            }
        }

        // 5. Realistic Running (Sprint-Jumping ONLY when chasing far away > 5.5m)
        if (currentState == CombatState.CHASING && horizontalDistance > 5.5 && !shouldBlock) {
            shouldSprint = true;
            forwardInput = 1.0f;
            if (isOnGround) {
                shouldJump = true; // Sprint-jump only when chasing from far away
            }
        }

        // 6. W-Tap Sprint Reset
        if (currentState == CombatState.W_TAP_RESET) {
            if (stateTicks >= 3) {
                // W-Tap reset complete! Re-engage sprint to close in and combo
                currentState = CombatState.CHASING;
                stateTicks = 0;
                forwardInput = 1.0f;
                shouldSprint = true;
            } else if (stateTicks == 0) {
                // Exact attack tick: KEEP SPRINTING so the attack registers as a sprint-hit!
                forwardInput = 1.0f;
                shouldSprint = true;
            } else {
                // Ticks 1 and 2: Cut forward movement and drop sprint (W-Tap release)
                forwardInput = 0.0f;
                shouldSprint = false;
            }
        }

        return new CombatAction(forwardInput, shouldSprint, shouldJump, shouldAttack, shouldBlock, switchSlot);
    }

    public void onTargetShieldBroken() {
        this.needSwordSwap = true;
    }

    public void onShieldBroken() {
        this.shieldBlockTicksRemaining = 0;
    }

    public int findSwordSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType().name().endsWith("_SWORD")) {
                return i;
            }
        }
        return -1;
    }

    private int findAxeSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType().name().endsWith("_AXE")) {
                return i;
            }
        }
        return -1;
    }

    public record CombatAction(
            float forwardInput,
            boolean sprint,
            boolean jump,
            boolean attack,
            boolean block,
            int switchHotbarSlot
    ) {}
}
