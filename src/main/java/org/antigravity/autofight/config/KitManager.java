package org.antigravity.autofight.config;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class KitManager {
    private final Map<String, Kit> kits = new HashMap<>();
    private final Logger logger;

    public record Kit(
            String name,
            ItemStack helmet,
            ItemStack chestplate,
            ItemStack leggings,
            ItemStack boots,
            ItemStack mainhand,
            ItemStack offhand,
            Map<Integer, ItemStack> hotbar
    ) {
        public void applyTo(Player player) {
            PlayerInventory inv = player.getInventory();
            inv.clear();
            if (helmet != null) inv.setHelmet(helmet.clone());
            if (chestplate != null) inv.setChestplate(chestplate.clone());
            if (leggings != null) inv.setLeggings(leggings.clone());
            if (boots != null) inv.setBoots(boots.clone());
            if (mainhand != null) inv.setItemInMainHand(mainhand.clone());
            if (offhand != null) inv.setItemInOffHand(offhand.clone());

            for (Map.Entry<Integer, ItemStack> entry : hotbar.entrySet()) {
                int slot = entry.getKey();
                if (slot >= 0 && slot < 36) {
                    inv.setItem(slot, entry.getValue().clone());
                }
            }
        }
    }

    public KitManager(Logger logger) {
        this.logger = logger;
    }

    public void loadKits(FileConfiguration config) {
        kits.clear();
        ConfigurationSection section = config.getConfigurationSection("kits");
        if (section == null) {
            logger.warning("No kits found in config.yml!");
            return;
        }

        for (String kitKey : section.getKeys(false)) {
            ConfigurationSection kitSec = section.getConfigurationSection(kitKey);
            if (kitSec == null) continue;

            ItemStack helmet = parseItem(kitSec.getConfigurationSection("helmet"));
            ItemStack chestplate = parseItem(kitSec.getConfigurationSection("chestplate"));
            ItemStack leggings = parseItem(kitSec.getConfigurationSection("leggings"));
            ItemStack boots = parseItem(kitSec.getConfigurationSection("boots"));
            ItemStack mainhand = parseItem(kitSec.getConfigurationSection("mainhand"));
            ItemStack offhand = parseItem(kitSec.getConfigurationSection("offhand"));

            Map<Integer, ItemStack> hotbar = new HashMap<>();
            ConfigurationSection hotbarSec = kitSec.getConfigurationSection("hotbar");
            if (hotbarSec != null) {
                for (String slotStr : hotbarSec.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(slotStr);
                        ItemStack item = parseItem(hotbarSec.getConfigurationSection(slotStr));
                        if (item != null) {
                            hotbar.put(slot, item);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            kits.put(kitKey.toLowerCase(), new Kit(kitKey, helmet, chestplate, leggings, boots, mainhand, offhand, hotbar));
            logger.info("Loaded kit: " + kitKey);
        }
    }

    public Kit getKit(String name) {
        if (name == null) return null;
        return kits.get(name.toLowerCase());
    }

    public boolean hasKit(String name) {
        return name != null && kits.containsKey(name.toLowerCase());
    }

    private ItemStack parseItem(ConfigurationSection sec) {
        if (sec == null) return null;
        String matName = sec.getString("material");
        if (matName == null) return null;

        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            logger.warning("Unknown material: " + matName);
            return null;
        }

        int amount = sec.getInt("amount", 1);
        ItemStack item = new ItemStack(mat, Math.max(1, amount));

        ConfigurationSection enchSec = sec.getConfigurationSection("enchantments");
        if (enchSec != null) {
            for (String enchKey : enchSec.getKeys(false)) {
                Enchantment ench = getEnchantment(enchKey);
                if (ench != null) {
                    int level = enchSec.getInt(enchKey, 1);
                    item.addUnsafeEnchantment(ench, level);
                } else {
                    logger.warning("Unknown enchantment: " + enchKey);
                }
            }
        }

        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) {
            String potType = sec.getString("potion-type");
            if (potType != null) {
                String clean = potType.toUpperCase().replace("SPEED", "SWIFTNESS").replace("INSTANT_HEALTH", "HEALING");
                try {
                    potionMeta.setBasePotionType(org.bukkit.potion.PotionType.valueOf(clean));
                    item.setItemMeta(potionMeta);
                } catch (Exception ignored) {}
            }
        }
        return item;
    }

    @SuppressWarnings("deprecation")
    private Enchantment getEnchantment(String name) {
        Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase()));
        if (ench != null) return ench;
        return Enchantment.getByName(name.toUpperCase());
    }
}
