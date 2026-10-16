package dev.koefi.boxpvp.bot;

import dev.koefi.boxpvp.arena.Arena;
import dev.koefi.boxpvp.combat.CombatTuning;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

final class BotRecovery {
    private final BotPlayer bot;
    private final BotBrain brain;
    private final CombatTuning tuning;
    private boolean active;
    private int started;
    private int pearlAfter;
    private Vec3 goal;
    private float yaw;
    private float pitch;

    BotRecovery(BotPlayer bot, BotBrain brain, CombatTuning tuning) {
        this.bot = bot;
        this.brain = brain;
        this.tuning = tuning;
    }

    boolean tick(double distance) {
        int apple = brain.find(stack -> stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE));
        boolean regeneration = bot.hasEffect(MobEffects.REGENERATION);
        if (!active && bot.getHealth() < tuning.healHealth() && !regeneration && apple >= 0) {
            active = true;
            started = bot.tickCount;
            goal = null;
        }
        if (!active) {
            return false;
        }
        brain.ignorePlayerPearl();
        if (bot.getHealth() >= Math.min(16, tuning.healHealth()) && regeneration || apple < 0 && !regeneration && !bot.isUsingItem()) {
            active = false;
            goal = null;
            return false;
        }
        int pearl = brain.find(stack -> stack.is(Items.ENDER_PEARL));
        boolean canPearl = pearl >= 0 && !bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(pearl))
            && bot.tickCount >= pearlAfter && bot.getHealth() > 3;
        boolean eating = bot.isUsingItem() && (bot.getUseItem().is(Items.GOLDEN_APPLE) || bot.getUseItem().is(Items.ENCHANTED_GOLDEN_APPLE));
        if (distance < 9 && canPearl && (!eating || distance < 3.5 && bot.getUseItemRemainingTicks() > 8)) {
            if (goal == null) {
                goal = retreatGoal();
                yaw = brain.desiredYaw(goal);
                pitch = PearlTrajectory.pitch(bot, goal, yaw);
            }
            brain.aimAngles(yaw, pitch);
            brain.move(0.9f, 0, !bot.isUsingItem());
            if (Math.abs(Mth.wrapDegrees(yaw - bot.getYRot())) < 3 && Math.abs(pitch - bot.getXRot()) < 2) {
                bot.stopUsingItem();
                if (brain.useOffhand(pearl)) {
                    brain.defensivePearls++;
                    pearlAfter = bot.tickCount + 80;
                    goal = null;
                }
            }
            return true;
        }
        if (eating) {
            flee(0.9f);
            return true;
        }
        if (!bot.getEnderPearls().isEmpty()) {
            flee(1);
            return true;
        }
        if (!regeneration && apple >= 0 && (distance >= 7 || bot.getHealth() <= 4 || bot.tickCount - started > 40)) {
            bot.stopUsingItem();
            brain.use(apple);
            return true;
        }
        if (regeneration && bot.getHealth() >= tuning.healHealth() || bot.tickCount - started > 160) {
            active = false;
            return false;
        }
        flee(distance < 12 ? 1 : 0.25f);
        return true;
    }

    private Vec3 retreatGoal() {
        Vec3 away = bot.position().subtract(bot.session.owner.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 0.1) {
            away = new Vec3(1, 0, 0);
        }
        away = away.normalize();
        Vec3 best = bot.position();
        double score = -Double.MAX_VALUE;
        for (int index = -3; index <= 3; index++) {
            Vec3 offset = away.yRot(index * 0.45f).scale(23);
            Vec3 candidate = new Vec3(Mth.clamp(bot.getX() + offset.x, Arena.MIN + 3, Arena.MAX - 3), Arena.FLOOR + 1,
                Mth.clamp(bot.getZ() + offset.z, Arena.MIN + 3, Arena.MAX - 3));
            double travel = candidate.distanceTo(bot.position());
            double separation = candidate.distanceTo(bot.session.owner.position());
            double value = separation - Math.abs(travel - 21) * 0.3;
            if (travel >= 9 && value > score) {
                score = value;
                best = candidate;
            }
        }
        return best;
    }

    private void flee(float forward) {
        Vec3 away = bot.position().subtract(bot.session.owner.position()).multiply(1, 0, 1).normalize();
        Vec3 destination = new Vec3(Mth.clamp(bot.getX() + away.x * 12, Arena.MIN + 3, Arena.MAX - 3), bot.getEyeY(),
            Mth.clamp(bot.getZ() + away.z * 12, Arena.MIN + 3, Arena.MAX - 3));
        if (destination.distanceToSqr(bot.position()) < 9) {
            destination = retreatGoal().add(0, bot.getEyeHeight(), 0);
        }
        brain.aim(destination, 0, 0);
        brain.move(forward, 0, true);
    }
}
