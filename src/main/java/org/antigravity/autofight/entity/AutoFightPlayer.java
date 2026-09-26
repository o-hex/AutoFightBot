package org.antigravity.autofight.entity;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;
import org.antigravity.autofight.AutoFightPlugin;
import org.antigravity.autofight.ai.CombatStateMachine;
import org.antigravity.autofight.ai.HeuristicEngine;
import org.antigravity.autofight.ai.TargetTracker;
import org.antigravity.autofight.config.DifficultyProfile;
import org.antigravity.autofight.config.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class AutoFightPlayer extends ServerPlayer {
    private final AutoFightPlugin plugin;
    private final String botName;
    private final DifficultyProfile difficulty;
    private final boolean isHumanized;
    private final KitManager.Kit kit;

    private final TargetTracker targetTracker;
    private final HeuristicEngine heuristicEngine;
    private final CombatStateMachine combatMachine;

    private BukkitTask aiTask;
    private long ticksLived = 0;
    private boolean isDespawned = false;

    // Healing & Consumables State
    private boolean isEating = false;
    private int eatingTicks = 0;
    private ItemStack savedOffhand = null;
    private Material eatingMaterial = null;
    private int ticksSinceLastPot = 30;
    private boolean isPottingRetreat = false;
    private int pottingRetreatTicks = 0;
    private int knockbackStunTicks = 0;

    // Shield & Combat Utility State
    private int shieldDisabledTicks = 0;
    private int ticksSinceLastPearl = 60;

    public AutoFightPlayer(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            AutoFightPlugin plugin,
            String botName,
            DifficultyProfile difficulty,
            boolean isHumanized,
            KitManager.Kit kit
    ) {
        super(server, level, profile, ClientInformation.createDefault());
        this.plugin = plugin;
        this.botName = botName;
        this.difficulty = difficulty;
        this.isHumanized = isHumanized;
        this.kit = kit;

        // Initialize Network Pipeline
        DummyConnection dummyConnection = new DummyConnection();
        this.connection = new DummyPacketListener(server, dummyConnection, this, CommonListenerCookie.createInitial(profile, false));

        // Initialize AI components
        this.targetTracker = new TargetTracker(
                getProfileId(profile),
                plugin.getAggroRadius(),
                plugin.getBypassPermission(),
                plugin.isTargetCreative(),
                plugin.isTargetSpectator()
        );
        this.heuristicEngine = new HeuristicEngine(difficulty, isHumanized);
        this.combatMachine = new CombatStateMachine(difficulty);
    }

    public static AutoFightPlayer spawn(
            AutoFightPlugin plugin,
            Location location,
            String name,
            Property skinProperty,
            KitManager.Kit kit,
            DifficultyProfile difficulty,
            boolean isHumanized
    ) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();

        UUID botUuid = UUID.randomUUID();
        GameProfile profile = createProfile(botUuid, name, skinProperty);

        AutoFightPlayer bot = new AutoFightPlayer(server, level, profile, plugin, name, difficulty, isHumanized, kit);

        // Position entity
        bot.setPos(location.getX(), location.getY(), location.getZ());
        bot.setRot(location.getYaw(), location.getPitch());
        bot.setYHeadRot(location.getYaw());
        bot.setYBodyRot(location.getYaw());

        // Apply equipment kit BEFORE placing into world/tracker so initial packets carry armor
        Player bukkitPlayer = bot.getBukkitEntity();
        if (kit != null) {
            kit.applyTo(bukkitPlayer);
        }

        // Add to server world and player list
        server.getPlayerList().placeNewPlayer(
                bot.connection.connection,
                bot,
                CommonListenerCookie.createInitial(profile, false)
        );

        // Broadcast equipment packet immediately so armor is 100% visible on client!
        bot.syncEquipment();

        // Start AI tick loop
        bot.startAiLoop();

        return bot;
    }

    private void startAiLoop() {
        this.aiTask = Bukkit.getScheduler().runTaskTimer(plugin, this::aiTick, 1L, 1L);
    }

    public void aiTick() {
        if (isDespawned || isDeadOrDying() || !getBukkitEntity().isOnline()) {
            return;
        }

        ticksLived++;
        ticksSinceLastPot++;
        ticksSinceLastPearl++;
        if (shieldDisabledTicks > 0) {
            shieldDisabledTicks--;
        }
        Player bukkitPlayer = getBukkitEntity();
        Location botLoc = bukkitPlayer.getLocation();

        // 0. Auto-equip armor if any slots are unequipped
        if (ticksLived % 10 == 1) {
            autoEquipArmor(bukkitPlayer);
        }

        // 1. Target Tracking
        Player target = targetTracker.updateTarget(botLoc);
        if (target == null) {
            // Idle state: no movement inputs
            return;
        }

        // 2. Heuristics observation
        heuristicEngine.recordTargetState(target, ticksLived);
        HeuristicEngine.TargetSnapshot snapshot = heuristicEngine.getPerceivedTargetState(ticksLived);
        Location targetLoc = (snapshot != null) ? snapshot.location() : target.getLocation();

        double dx = botLoc.getX() - targetLoc.getX();
        double dz = botLoc.getZ() - targetLoc.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double eyeDist = bukkitPlayer.getEyeLocation().distance(target.getEyeLocation());

        // 3. Realistic Potting with Tactical Retreat (Don't pot the enemy!)
        double health = bukkitPlayer.getHealth();

        // A. Emergency Healing Pot (Instant Health)
        if (!isPottingRetreat && !isEating && health <= 11.0 && ticksSinceLastPot >= 25) {
            int healSlot = findSplashPotionSlot(bukkitPlayer, "HEALING");
            if (healSlot != -1) {
                if (horizontalDist < 4.5) {
                    // Enemy is inside splash radius! Retreat so we don't heal the enemy!
                    isPottingRetreat = true;
                    pottingRetreatTicks = 0;
                } else {
                    // Safe distance (>= 4.5m) - splash immediately
                    executeSplashPot(bukkitPlayer, botLoc, healSlot, "HEALING");
                }
            }
        }

        if (isPottingRetreat) {
            pottingRetreatTicks++;
            if (horizontalDist >= 4.5 || pottingRetreatTicks >= 12) {
                // Safe distance achieved or timeout: execute splash pot at feet
                int healSlot = findSplashPotionSlot(bukkitPlayer, "HEALING");
                if (healSlot != -1) {
                    executeSplashPot(bukkitPlayer, botLoc, healSlot, "HEALING");
                }
                isPottingRetreat = false;
                pottingRetreatTicks = 0;
            } else {
                // Sprint-jump away from target to build distance rapidly
                Vector awayVec = botLoc.toVector().subtract(targetLoc.toVector()).setY(0);
                if (awayVec.lengthSquared() > 0.001) {
                    awayVec.normalize();
                } else {
                    awayVec = new Vector(1, 0, 0);
                }
                float awayYaw = (float) Math.toDegrees(Math.atan2(-awayVec.getX(), awayVec.getZ()));
                setYRot(awayYaw);
                setYHeadRot(awayYaw);
                setYBodyRot(awayYaw);
                setXRot(0.0f);
                setSprinting(true);

                float forward = 1.0f;
                if (onGround()) {
                    jumpFromGround();
                }
                Vec3 retreatInput = new Vec3(0.0, 0.0, forward);
                travel(retreatInput);
                return; // Skip combat attacks this tick while disengaging
            }
        }

        // B. Speed II & Strength II Buffing (Pre-potting & Safe Mid-fight Re-potting)
        // Only splash positive buffs when enemy is >= 5.0m away to ensure 0% splash buff on opponent!
        if (!isPottingRetreat && !isEating && ticksSinceLastPot >= 8 && horizontalDist >= 5.0) {
            boolean shouldBuff = (health > 14.0) || (horizontalDist >= 6.5);
            if (shouldBuff) {
                if (!bukkitPlayer.hasPotionEffect(PotionEffectType.SPEED)) {
                    int speedSlot = findSplashPotionSlot(bukkitPlayer, "SWIFTNESS");
                    if (speedSlot != -1) {
                        executeSplashPot(bukkitPlayer, botLoc, speedSlot, "SWIFTNESS");
                    }
                } else if (!bukkitPlayer.hasPotionEffect(PotionEffectType.STRENGTH)) {
                    int strengthSlot = findSplashPotionSlot(bukkitPlayer, "STRENGTH");
                    if (strengthSlot != -1) {
                        executeSplashPot(bukkitPlayer, botLoc, strengthSlot, "STRENGTH");
                    }
                }
            }
        }

        // 4. Realistic Healing (Golden Apple Consumption)
        if (!isEating && health <= 14.0 && !bukkitPlayer.hasPotionEffect(PotionEffectType.REGENERATION)) {
            int gappleSlot = findGappleSlot(bukkitPlayer);
            if (gappleSlot != -1) {
                ItemStack gapple = bukkitPlayer.getInventory().getItem(gappleSlot);
                if (gapple != null) {
                    isEating = true;
                    eatingTicks = 0;
                    eatingMaterial = gapple.getType();

                    // Stash current offhand (e.g. shield)
                    ItemStack off = bukkitPlayer.getInventory().getItemInOffHand();
                    savedOffhand = (off != null && off.getType() != Material.AIR) ? off.clone() : null;

                    // Place 1 apple in offhand and start eating
                    bukkitPlayer.getInventory().setItemInOffHand(new ItemStack(eatingMaterial, 1));
                    startUsingItem(InteractionHand.OFF_HAND);
                    syncEquipment();
                }
            }
        }

        if (isEating) {
            eatingTicks++;
            // Eating sound and particles every 4 ticks
            if (eatingTicks % 4 == 0) {
                bukkitPlayer.getWorld().playSound(botLoc, Sound.ENTITY_GENERIC_EAT, 1.0f, 0.9f + (float) Math.random() * 0.2f);
                bukkitPlayer.getWorld().spawnParticle(Particle.ITEM, botLoc.clone().add(0, 1.4, 0), 6, 0.1, 0.1, 0.1, 0.05, new ItemStack(eatingMaterial));
            }

            // Finish eating after 32 ticks (1.6 seconds)
            if (eatingTicks >= 32) {
                bukkitPlayer.getWorld().playSound(botLoc, Sound.ENTITY_PLAYER_BURP, 1.0f, 1.0f);

                // Consume 1 apple from inventory
                consumeOneGapple(bukkitPlayer, eatingMaterial);

                // Apply regeneration and absorption
                if (eatingMaterial == Material.ENCHANTED_GOLDEN_APPLE) {
                    bukkitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 400, 1));
                    bukkitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 3));
                    bukkitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 6000, 0));
                    bukkitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0));
                } else {
                    bukkitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
                    bukkitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
                }

                stopUsingItem();
                // Restore previous offhand item (shield)
                bukkitPlayer.getInventory().setItemInOffHand(savedOffhand != null ? savedOffhand : new ItemStack(Material.AIR));
                savedOffhand = null;
                isEating = false;
                eatingMaterial = null;
                syncEquipment();
            }
        }

        // 4.5. Ender Pearl Chasing (when target is fleeing or beyond melee chase distance)
        boolean threwPearl = false;
        if (!isEating && !isPottingRetreat && horizontalDist >= 7.5 && horizontalDist <= 55.0 && ticksSinceLastPearl >= 30) {
            int pearlSlot = findPearlSlot(bukkitPlayer);
            if (pearlSlot != -1 && !bukkitPlayer.hasCooldown(Material.ENDER_PEARL)) {
                threwPearl = executePearlThrow(bukkitPlayer, targetLoc, horizontalDist);
            }
        }

        // 5. Aim & Rotation
        if (!threwPearl) {
            Vector rot = heuristicEngine.calculateRotation(
                    getYRot(),
                    getXRot(),
                    bukkitPlayer.getEyeLocation(),
                    targetLoc,
                    targetTracker.getTargetVelocity()
            );

            float newYaw = (float) rot.getX();
            float newPitch = (float) rot.getY();
            setYRot(newYaw);
            setXRot(newPitch);
            setYHeadRot(newYaw);
            setYBodyRot(newYaw);
        }

        // 6. Combat State Machine & Tactics
        boolean isFalling = getDeltaMovement().y < -0.08 && !onGround();
        float cooldownCharge = bukkitPlayer.getAttackCooldown();

        CombatStateMachine.CombatAction action = combatMachine.updateCombat(
                bukkitPlayer,
                target,
                horizontalDist,
                eyeDist,
                isFalling,
                onGround(),
                cooldownCharge
        );

        // Hotbar switching (e.g. axe for shield breaking)
        if (action.switchHotbarSlot() >= 0 && action.switchHotbarSlot() < 9) {
            bukkitPlayer.getInventory().setHeldItemSlot(action.switchHotbarSlot());
            syncEquipment();
        }

        // Melee attacking & Jump Crits (Strict Vanilla Survival Reach <= 2.95m / 3.15m eye)
        boolean inMeleeReach = horizontalDist <= 2.95 && eyeDist <= 3.15;
        if (action.attack() && !isEating && inMeleeReach) {
            if (isUsingItem()) {
                stopUsingItem();
            }

            // Always ensure full weapon attack charge
            this.attackStrengthTicker = 100;

            if (!onGround() || isFalling) {
                // Critical Hit Requirements in Vanilla:
                // 1. fallDistance > 0.0f
                // 2. isSprinting() == false
                setSprinting(false);
                bukkitPlayer.setFallDistance(Math.max(bukkitPlayer.getFallDistance(), 0.75f));

                swing(InteractionHand.MAIN_HAND, true);
                this.attack(((CraftPlayer) target).getHandle());

                // Guaranteed visual and audio crit feedback
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
                target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1.2, 0), 16, 0.3, 0.3, 0.3, 0.15);
            } else {
                if (action.sprint() && action.forwardInput() > 0) {
                    setSprinting(true);
                }
                swing(InteractionHand.MAIN_HAND, true);
                this.attack(((CraftPlayer) target).getHandle());
            }
        }

        // Shield blocking (only if not attacking, not eating, and shield not disabled)
        if (!isEating && !isShieldDisabled()) {
            if (action.block() && !action.attack() && bukkitPlayer.getInventory().getItemInOffHand().getType().name().equals("SHIELD")) {
                if (!isUsingItem()) {
                    startUsingItem(InteractionHand.OFF_HAND);
                }
            } else if (isUsingItem() && !action.block() && bukkitPlayer.getInventory().getItemInOffHand().getType().name().equals("SHIELD")) {
                stopUsingItem();
            }
        } else if (isUsingItem() && bukkitPlayer.getInventory().getItemInOffHand().getType().name().equals("SHIELD")) {
            stopUsingItem();
        }

        // Sprint control
        if (isEating) {
            setSprinting(false);
        } else {
            setSprinting(action.sprint() && action.forwardInput() > 0 && !isUsingItem());
        }

        // 7. Jump & Obstacle Check
        boolean shouldJump = action.jump();
        if (horizontalCollision && onGround()) {
            // Step over obstacles / jump up blocks
            shouldJump = true;
        }

        if (shouldJump && onGround()) {
            jumpFromGround();
        }

        // 8. Kinematic Movement Inputs (WASD + Strafe)
        float strafe = heuristicEngine.getDesiredStrafe(horizontalDist);
        float forward = action.forwardInput();

        // While taking damage knockback, cut forward drive so natural parabolic knockback moves the bot
        if (knockbackStunTicks > 0) {
            knockbackStunTicks--;
            forward = 0.0f;
            strafe = 0.0f;
            setSprinting(false);
        } else if (isEating && horizontalDist < 3.5) {
            forward = -0.4f;
        } else if (horizontalDist < 0.9 && !action.jump() && !action.attack() && cooldownCharge < 0.8f) {
            // Only micro-step back if weapon is on cooldown and overlapping
            forward = -0.15f;
        }

        Vec3 movementInput = new Vec3(strafe, 0.0, forward);
        travel(movementInput);
    }

    public void syncEquipment() {
        try {
            List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> list = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                list.add(Pair.of(slot, this.getItemBySlot(slot)));
            }
            ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(this.getId(), list);
            for (Player p : getBukkitEntity().getWorld().getPlayers()) {
                if (p.getUniqueId().equals(this.getUUID())) continue;
                ((CraftPlayer) p).getHandle().connection.send(packet);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to sync equipment: " + e.getMessage());
        }
    }

    private void autoEquipArmor(Player bukkitPlayer) {
        PlayerInventory inv = bukkitPlayer.getInventory();
        boolean changed = false;

        if (inv.getHelmet() == null || inv.getHelmet().getType() == Material.AIR) {
            int slot = findArmorSlot(inv, "_HELMET");
            if (slot != -1) {
                inv.setHelmet(inv.getItem(slot));
                inv.setItem(slot, null);
                changed = true;
            }
        }
        if (inv.getChestplate() == null || inv.getChestplate().getType() == Material.AIR) {
            int slot = findArmorSlot(inv, "_CHESTPLATE");
            if (slot != -1) {
                inv.setChestplate(inv.getItem(slot));
                inv.setItem(slot, null);
                changed = true;
            }
        }
        if (inv.getLeggings() == null || inv.getLeggings().getType() == Material.AIR) {
            int slot = findArmorSlot(inv, "_LEGGINGS");
            if (slot != -1) {
                inv.setLeggings(inv.getItem(slot));
                inv.setItem(slot, null);
                changed = true;
            }
        }
        if (inv.getBoots() == null || inv.getBoots().getType() == Material.AIR) {
            int slot = findArmorSlot(inv, "_BOOTS");
            if (slot != -1) {
                inv.setBoots(inv.getItem(slot));
                inv.setItem(slot, null);
                changed = true;
            }
        }

        if (changed) {
            syncEquipment();
        }
    }

    private int findArmorSlot(PlayerInventory inv, String suffix) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType().name().endsWith(suffix)) {
                return i;
            }
        }
        return -1;
    }

    private void executeSplashPot(Player bukkitPlayer, Location botLoc, int potSlot, String potType) {
        if (potSlot < 0 || potSlot >= 36) return;

        setXRot(85.0f);

        ItemStack potItem = bukkitPlayer.getInventory().getItem(potSlot);
        if (potItem != null) {
            if (potItem.getAmount() > 1) {
                potItem.setAmount(potItem.getAmount() - 1);
            } else {
                bukkitPlayer.getInventory().setItem(potSlot, null);
            }
        }

        bukkitPlayer.getWorld().playSound(botLoc, Sound.ENTITY_SPLASH_POTION_THROW, 1.0f, 1.0f);
        bukkitPlayer.getWorld().playSound(botLoc, Sound.ENTITY_SPLASH_POTION_BREAK, 1.0f, 1.0f);
        bukkitPlayer.getWorld().spawnParticle(Particle.SPLASH, botLoc.clone().add(0, 0.2, 0), 20, 0.4, 0.2, 0.4, 0.05);

        if (potType.equals("HEALING")) {
            bukkitPlayer.getWorld().spawnParticle(Particle.HEART, botLoc.clone().add(0, 1.0, 0), 6, 0.3, 0.3, 0.3, 0.05);
            double maxHealth = bukkitPlayer.getMaxHealth();
            bukkitPlayer.setHealth(Math.min(maxHealth, bukkitPlayer.getHealth() + 8.0));
        } else if (potType.equals("SWIFTNESS")) {
            bukkitPlayer.getWorld().spawnParticle(Particle.ENTITY_EFFECT, botLoc.clone().add(0, 1.0, 0), 16, 0.2, 0.5, 0.2, org.bukkit.Color.AQUA);
            bukkitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 1800, 1));
        } else if (potType.equals("STRENGTH")) {
            bukkitPlayer.getWorld().spawnParticle(Particle.ENTITY_EFFECT, botLoc.clone().add(0, 1.0, 0), 16, 0.2, 0.5, 0.2, org.bukkit.Color.RED);
            bukkitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 1800, 1));
        }

        ticksSinceLastPot = 0;
        syncEquipment();
    }

    private int findSplashPotionSlot(Player player, String typeKeyword) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.SPLASH_POTION) {
                if (item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta) {
                    org.bukkit.potion.PotionType type = meta.getBasePotionType();
                    if (type != null && type.name().contains(typeKeyword)) {
                        return i;
                    }
                }
            }
        }
        // Fallback for generic healing splash potion if specific type keyword not found
        if (typeKeyword.equals("HEALING")) {
            for (int i = 0; i < 36; i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (item != null && item.getType() == Material.SPLASH_POTION) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findGappleSlot(Player player) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.GOLDEN_APPLE) {
                return i;
            }
        }
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
                return i;
            }
        }
        return -1;
    }

    private void consumeOneGapple(Player player, Material mat) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == mat) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    player.getInventory().setItem(i, null);
                }
                return;
            }
        }
    }

    private int findPearlSlot(Player player) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.ENDER_PEARL) {
                return i;
            }
        }
        return -1;
    }

    private boolean executePearlThrow(Player bukkitPlayer, Location targetLoc, double horizontalDist) {
        int pearlSlot = findPearlSlot(bukkitPlayer);
        if (pearlSlot == -1) return false;

        ItemStack pearlItem = bukkitPlayer.getInventory().getItem(pearlSlot);
        if (pearlItem != null) {
            if (pearlItem.getAmount() > 1) {
                pearlItem.setAmount(pearlItem.getAmount() - 1);
            } else {
                bukkitPlayer.getInventory().setItem(pearlSlot, null);
            }
        }

        Location eyeLoc = bukkitPlayer.getEyeLocation();

        // Lead moving target slightly based on approximate pearl flight time
        Vector targetVel = targetTracker.getTargetVelocity();
        double flightTimeApprox = horizontalDist / 1.45;
        double leadFactor = Math.min(2.0, flightTimeApprox * 0.35);

        double targetX = targetLoc.getX() + (targetVel != null ? targetVel.getX() * leadFactor : 0);
        double targetZ = targetLoc.getZ() + (targetVel != null ? targetVel.getZ() * leadFactor : 0);
        // Aim for target's feet / lower body
        double targetY = targetLoc.getY() + 0.2;

        double dx = targetX - eyeLoc.getX();
        double dz = targetZ - eyeLoc.getZ();
        double dh = Math.sqrt(dx * dx + dz * dz);
        if (dh < 0.1) return false;

        double dirX = dx / dh;
        double dirZ = dz / dh;

        // Ballistic formula for Minecraft Ender Pearl physics:
        // Horizontal launch speed = 1.45 blocks/tick
        // Flight ticks T = dh / 1.45
        // Vertical drop compensation: g = 0.03, half_g = 0.015
        double t = dh / 1.45;
        double dy = targetY - eyeLoc.getY();
        double vy = (dy / t) + (0.015 * t);
        // Clamp vertical launch velocity to realistic limits [-0.5, 1.25]
        vy = Math.max(-0.5, Math.min(1.25, vy));

        Vector pearlVel = new Vector(dirX * 1.45, vy, dirZ * 1.45);

        // Visual aim: look along the exact pearl launch trajectory
        double hSpeed = Math.sqrt(pearlVel.getX() * pearlVel.getX() + pearlVel.getZ() * pearlVel.getZ());
        float pearlPitch = (float) -Math.toDegrees(Math.atan2(pearlVel.getY(), hSpeed));
        float pearlYaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

        setYRot(pearlYaw);
        setXRot(pearlPitch);
        setYHeadRot(pearlYaw);
        setYBodyRot(pearlYaw);

        bukkitPlayer.launchProjectile(org.bukkit.entity.EnderPearl.class, pearlVel);
        bukkitPlayer.swingMainHand();
        bukkitPlayer.getWorld().playSound(eyeLoc, Sound.ENTITY_ENDER_PEARL_THROW, 1.0f, 1.0f);
        bukkitPlayer.setCooldown(Material.ENDER_PEARL, 30);
        ticksSinceLastPearl = 0;
        syncEquipment();
        return true;
    }

    public void disableShield(int ticks) {
        this.shieldDisabledTicks = ticks;
        Player bukkitPlayer = getBukkitEntity();
        bukkitPlayer.setCooldown(Material.SHIELD, ticks);
        if (isUsingItem()) {
            stopUsingItem();
        }
        combatMachine.onShieldBroken();
        bukkitPlayer.getWorld().playSound(bukkitPlayer.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f);
        bukkitPlayer.getWorld().spawnParticle(Particle.ITEM, bukkitPlayer.getLocation().add(0, 1.2, 0), 15, 0.2, 0.2, 0.2, 0.05, new ItemStack(Material.SHIELD));
        try {
            this.level().broadcastEntityEvent(this, (byte) 30);
        } catch (Exception ignored) {}
    }

    public boolean isShieldDisabled() {
        return shieldDisabledTicks > 0 || getBukkitEntity().hasCooldown(Material.SHIELD);
    }

    public CombatStateMachine getCombatMachine() {
        return combatMachine;
    }

    @Override
    public void die(DamageSource damageSource) {
        super.die(damageSource);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "☠ " + ChatColor.RED + "AutoFight bot " + botName + " was slain!");
        despawn();
    }

    public void despawn() {
        if (isDespawned) return;
        isDespawned = true;

        if (isEating && savedOffhand != null) {
            getBukkitEntity().getInventory().setItemInOffHand(savedOffhand);
        }

        if (aiTask != null && !aiTask.isCancelled()) {
            aiTask.cancel();
        }

        plugin.unregisterBot(getUUID());

        // Cleanly remove player entity from world and server player list
        try {
            ((CraftServer) Bukkit.getServer()).getServer().getPlayerList().remove(this);
        } catch (Exception ignored) {}
        this.discard();
    }

    public String getBotName() {
        return botName;
    }

    public Player getBukkitPlayer() {
        return getBukkitEntity();
    }

    public void onDamagedBy(org.bukkit.entity.Entity damager, Vector dir, boolean isSprintHit) {
        this.knockbackStunTicks = isSprintHit ? 9 : 6;
        setSprinting(false);

        // Realistic Minecraft PvP knockback forces: launches 2.5 - 4.5 blocks
        double hForce = isSprintHit ? 1.45 : 0.95;
        double vForce = onGround() ? 0.40 : 0.26;

        Vector bukkitVel = new Vector(dir.getX() * hForce, vForce, dir.getZ() * hForce);
        getBukkitEntity().setVelocity(bukkitVel);
        Vec3 kbMotion = new Vec3(bukkitVel.getX(), bukkitVel.getY(), bukkitVel.getZ());
        this.setDeltaMovement(kbMotion);

        try {
            ClientboundSetEntityMotionPacket packet = new ClientboundSetEntityMotionPacket(this.getId(), kbMotion);
            for (Player p : getBukkitEntity().getWorld().getPlayers()) {
                if (p.getUniqueId().equals(this.getUUID())) continue;
                ((CraftPlayer) p).getHandle().connection.send(packet);
            }
        } catch (Exception ignored) {}
    }

    private static GameProfile createProfile(UUID botUuid, String name, Property skinProperty) {
        try {
            Class<?> arrayListMultimapClass = Class.forName("com.google.common.collect.ArrayListMultimap");
            Object multimap = arrayListMultimapClass.getMethod("create").invoke(null);
            if (skinProperty != null) {
                multimap.getClass().getMethod("put", Object.class, Object.class).invoke(multimap, "textures", skinProperty);
            }
            Class<?> multimapClass = Class.forName("com.google.common.collect.Multimap");
            java.lang.reflect.Constructor<?> pmCons = PropertyMap.class.getConstructor(multimapClass);
            Object propertyMap = pmCons.newInstance(multimap);

            java.lang.reflect.Constructor<?> gpCons = GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class);
            return (GameProfile) gpCons.newInstance(botUuid, name, propertyMap);
        } catch (Exception e) {
            try {
                GameProfile profile = new GameProfile(botUuid, name);
                if (skinProperty != null) {
                    try {
                        profile.getProperties().put("textures", skinProperty);
                    } catch (Exception ignored) {}
                }
                return profile;
            } catch (Exception ex) {
                throw new RuntimeException("Could not create GameProfile", ex);
            }
        }
    }

    private static UUID getProfileId(GameProfile profile) {
        try {
            return (UUID) profile.getClass().getMethod("id").invoke(profile);
        } catch (NoSuchMethodException e) {
            try {
                return (UUID) profile.getClass().getMethod("getId").invoke(profile);
            } catch (Exception ex) {
                return UUID.randomUUID();
            }
        } catch (Exception e) {
            return UUID.randomUUID();
        }
    }
}
