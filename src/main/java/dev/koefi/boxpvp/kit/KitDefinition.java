package dev.koefi.boxpvp.kit;

import dev.koefi.boxpvp.combat.CombatRule;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public record KitDefinition(String id, String name, CombatRule rule, List<ItemStack> items, boolean custom) {
    public KitDefinition {
        items = items.stream().map(ItemStack::copy).toList();
    }

    public void equip(ServerPlayer player) {
        player.stopUsingItem();
        player.getInventory().clearContent();
        for (int slot = 0; slot < items.size(); slot++) {
            player.getInventory().setItem(slot, items.get(slot).copy());
        }
        player.getInventory().setSelectedSlot(0);
        player.getInventory().setChanged();
        player.inventoryMenu.sendAllDataToRemote();
    }

    public List<String> description() {
        List<String> lines = new ArrayList<>();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                String line = stack.getHoverName().getString() + " ×" + stack.getCount();
                if (!lines.contains(line)) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }
}
