package dev.koefi.boxpvp.bot;

import dev.koefi.boxpvp.arena.Arena;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

final class BotFlight {
    private final BotPlayer bot;
    private final BotBrain brain;
    private int armorSlot = -1;
    private int nextBoost;
    private int nextFlight;

    BotFlight(BotPlayer bot, BotBrain brain) {
        this.bot = bot;
        this.brain = brain;
    }

    boolean tick() {
        if (bot.onGround()) {
            restore();
            return false;
        }
        Vec3 target = bot.session.owner.position();
        double horizontal = bot.position().subtract(target).horizontalDistance();
        double height = bot.getY() - Arena.FLOOR - 1;
        if (armorSlot >= 0) {
            if (horizontal < 4 && height < 14 || height < 2.5 || !bot.isFallFlying()) {
                restore();
                return false;
            }
            float pitch = horizontal > 12 ? height < 10 ? -12 : -4 : 30;
            brain.aimAngles(brain.desiredYaw(target), pitch);
            brain.move(1, 0, false);
            if (horizontal > 10 && bot.tickCount >= nextBoost) {
                int rocket = brain.find(stack -> stack.is(Items.FIREWORK_ROCKET));
                if (rocket >= 0 && brain.useOffhand(rocket)) {
                    brain.rocketsUsed++;
                    nextBoost = bot.tickCount + 35;
                }
            }
            return true;
        }
        double remaining = Math.sqrt(Math.max(0, height) * 2 / 0.08);
        double reachable = bot.getDeltaMovement().horizontalDistance() * remaining + remaining * 0.2;
        if (bot.tickCount < nextFlight || height < 8 || horizontal < Math.max(10, reachable) || bot.getDeltaMovement().y > 0.1) {
            return false;
        }
        int elytra = brain.find(stack -> stack.is(Items.ELYTRA) && stack.getDamageValue() < stack.getMaxDamage() - 1);
        if (elytra < 0 || elytra >= 36) {
            return false;
        }
        bot.stopUsingItem();
        ItemStack armor = bot.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack wings = bot.getInventory().getItem(elytra);
        bot.getInventory().setItem(elytra, armor);
        bot.setItemSlot(EquipmentSlot.CHEST, wings);
        armorSlot = elytra;
        brain.aimAngles(brain.desiredYaw(target), Mth.clamp(bot.getXRot(), -10, 10));
        if (!bot.tryToStartFallFlying()) {
            restore();
            return false;
        }
        brain.flightsStarted++;
        nextBoost = bot.tickCount;
        return true;
    }

    private void restore() {
        if (armorSlot < 0) {
            return;
        }
        bot.stopFallFlying();
        ItemStack wings = bot.getItemBySlot(EquipmentSlot.CHEST);
        bot.setItemSlot(EquipmentSlot.CHEST, bot.getInventory().getItem(armorSlot));
        bot.getInventory().setItem(armorSlot, wings);
        armorSlot = -1;
        nextFlight = bot.tickCount + 20;
    }
}
