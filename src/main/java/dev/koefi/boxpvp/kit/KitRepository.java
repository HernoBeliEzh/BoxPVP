package dev.koefi.boxpvp.kit;

import dev.koefi.boxpvp.combat.CombatRule;
import dev.koefi.boxpvp.storage.WorldFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

public final class KitRepository {
    private final Map<String, KitDefinition> kits = new LinkedHashMap<>();
    private final ServerPlayer owner;
    private final Path path;

    public KitRepository(ServerPlayer owner) throws IOException {
        this.owner = owner;
        path = WorldFiles.directory(owner.level().getServer()).resolve("kits-" + owner.getUUID() + ".nbt");
        addPreset("op", "OP", CombatRule.NORMAL);
        addPreset("axe", "Топор", CombatRule.NORMAL);
        addPreset("mace", "Булава", CombatRule.NORMAL);
        addPreset("critical", "Криты", CombatRule.CRITICAL);
        addPreset("combo", "Комба", CombatRule.COMBO);
        if (Files.exists(path)) {
            var ops = owner.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            for (var entry : WorldFiles.read(path).getListOrEmpty("kits")) {
                CompoundTag kit = (CompoundTag) entry;
                List<ItemStack> items = emptyItems();
                for (var itemTag : kit.getListOrEmpty("items")) {
                    CompoundTag item = (CompoundTag) itemTag;
                    int slot = item.getIntOr("slot", -1);
                    if (slot >= 0 && slot < items.size()) {
                        items.set(slot, ItemStack.CODEC.parse(ops, item.getCompoundOrEmpty("stack")).getOrThrow());
                    }
                }
                String id = kit.getStringOr("id", "");
                if (id.startsWith("custom-")) {
                    kits.put(id, new KitDefinition(id, kit.getStringOr("name", "Свой набор"), CombatRule.NORMAL, items, true));
                }
            }
        }
    }

    public List<KitDefinition> all() {
        return List.copyOf(kits.values());
    }

    public KitDefinition get(String id) {
        KitDefinition kit = kits.get(id);
        if (kit == null) {
            throw new IllegalArgumentException("Набор не найден");
        }
        return kit;
    }

    public KitDefinition withProtection(KitDefinition kit, int protection) {
        if (protection < 0 || protection > 4) {
            throw new IllegalArgumentException("Защита должна быть от 0 до IV");
        }
        List<ItemStack> items = kit.items().stream().map(ItemStack::copy).toList();
        var registry = owner.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack item = items.get(slot);
            if (slot >= 36 && slot <= 39 && !item.isEmpty()) {
                net.minecraft.world.item.enchantment.EnchantmentHelper.updateEnchantments(item, mutable -> mutable.set(registry.getOrThrow(Enchantments.PROTECTION), protection));
            }
            if (item.is(net.minecraft.tags.ItemTags.SWORDS) || item.is(net.minecraft.tags.ItemTags.AXES) || item.is(net.minecraft.tags.ItemTags.SPEARS)) {
                net.minecraft.world.item.enchantment.EnchantmentHelper.updateEnchantments(item, mutable -> mutable.set(registry.getOrThrow(Enchantments.SHARPNESS), Math.max(2, protection + 1)));
            }
        }
        return new KitDefinition(kit.id(), kit.name(), kit.rule(), items, kit.custom());
    }

    public void saveCurrent(String name) throws IOException {
        name = name.strip();
        if (name.isEmpty() || name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Название должно содержать от 1 до 32 символов");
        }
        if (kits.size() >= 37) {
            throw new IllegalArgumentException("Можно сохранить до 32 своих наборов");
        }
        List<ItemStack> items = emptyItems();
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, owner.getInventory().getItem(slot).copy());
        }
        String id = "custom-" + UUID.randomUUID();
        KitDefinition created = new KitDefinition(id, name, CombatRule.NORMAL, items, true);
        kits.put(id, created);
        try {
            persist();
        } catch (IOException exception) {
            kits.remove(id);
            throw exception;
        }
    }

    public void delete(String id) throws IOException {
        KitDefinition kit = get(id);
        if (!kit.custom()) {
            throw new IllegalArgumentException("Нельзя удалить стандартный набор");
        }
        kits.remove(id);
        try {
            persist();
        } catch (IOException exception) {
            kits.put(id, kit);
            throw exception;
        }
    }

    private void persist() throws IOException {
        CompoundTag root = new CompoundTag();
        ListTag saved = new ListTag();
        var ops = owner.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (KitDefinition kit : kits.values()) {
            if (!kit.custom()) {
                continue;
            }
            CompoundTag data = new CompoundTag();
            data.putString("id", kit.id());
            data.putString("name", kit.name());
            ListTag items = new ListTag();
            for (int slot = 0; slot < kit.items().size(); slot++) {
                ItemStack stack = kit.items().get(slot);
                if (!stack.isEmpty()) {
                    CompoundTag item = new CompoundTag();
                    item.putInt("slot", slot);
                    item.put("stack", ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
                    items.add(item);
                }
            }
            data.put("items", items);
            saved.add(data);
        }
        root.put("kits", saved);
        WorldFiles.write(path, root);
    }

    private List<ItemStack> emptyItems() {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
            items.add(ItemStack.EMPTY);
        }
        return items;
    }

    private ItemStack enchanted(Item item, int protection) {
        ItemStack stack = new ItemStack(item);
        enchant(stack, Enchantments.UNBREAKING, 3);
        enchant(stack, Enchantments.MENDING, 1);
        if (protection > 0) {
            enchant(stack, Enchantments.PROTECTION, protection);
        }
        return stack;
    }

    private void enchant(ItemStack stack, ResourceKey<Enchantment> key, int level) {
        stack.enchant(owner.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), level);
    }

    private void addPreset(String id, String name, CombatRule rule) {
        List<ItemStack> items = emptyItems();
        boolean axe = id.equals("axe");
        boolean mace = id.equals("mace");
        Item[] armor = axe
            ? new Item[]{Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET}
            : new Item[]{Items.NETHERITE_BOOTS, Items.NETHERITE_LEGGINGS, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_HELMET};
        for (int index = 0; index < armor.length; index++) {
            items.set(36 + index, enchanted(armor[index], axe ? 2 : 3));
        }
        ItemStack weapon = enchanted(axe ? Items.DIAMOND_AXE : mace ? Items.MACE : Items.NETHERITE_SWORD, 0);
        if (mace) {
            enchant(weapon, Enchantments.DENSITY, 5);
            enchant(weapon, Enchantments.WIND_BURST, 3);
        } else {
            enchant(weapon, Enchantments.SHARPNESS, axe ? 3 : 4);
        }
        items.set(0, weapon);
        items.set(1, new ItemStack(Items.GOLDEN_APPLE, axe || mace ? 16 : 64));
        items.set(2, new ItemStack(Items.ENDER_PEARL, 16));
        items.set(3, new ItemStack(Items.EXPERIENCE_BOTTLE, 64));
        items.set(4, new ItemStack(Items.COOKED_BEEF, 64));
        if (axe) {
            ItemStack sword = enchanted(Items.DIAMOND_SWORD, 0);
            enchant(sword, Enchantments.SHARPNESS, 3);
            items.set(5, sword);
        }
        if (mace) {
            ItemStack spear = enchanted(Items.NETHERITE_SPEAR, 0);
            enchant(spear, Enchantments.LUNGE, 3);
            items.set(5, spear);
            items.set(6, enchanted(Items.ELYTRA, 0));
            ItemStack rockets = new ItemStack(Items.FIREWORK_ROCKET, 32);
            rockets.set(DataComponents.FIREWORKS, new net.minecraft.world.item.component.Fireworks(1, List.of()));
            items.set(7, rockets);
        }
        if (axe) {
            items.set(40, enchanted(Items.SHIELD, 0));
        } else if (mace) {
            items.set(40, new ItemStack(Items.WIND_CHARGE, 64));
        } else {
            for (int index = 0; index < 3; index++) {
                ItemStack strength = new ItemStack(Items.SPLASH_POTION);
                strength.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_STRENGTH));
                items.set(5 + index, strength);
                ItemStack speed = new ItemStack(Items.SPLASH_POTION);
                speed.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_SWIFTNESS));
                items.set(9 + index, speed);
            }
        }
        kits.put(id, new KitDefinition(id, name, rule, items, false));
    }
}
