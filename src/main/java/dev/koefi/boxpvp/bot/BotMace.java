package dev.koefi.boxpvp.bot;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class BotMace {
    private final BotPlayer bot;
    private final BotBrain brain;

    BotMace(BotPlayer bot, BotBrain brain) {
        this.bot = bot;
        this.brain = brain;
    }

    void move(Vec3 target, Vec3 targetMotion) {
        int ticks = contactTicks(target.y + 0.5);
        Vec3 acceleration = requiredAcceleration(target, targetMotion, ticks);
        double yaw = Math.toRadians(bot.getYRot());
        double forward = -Math.sin(yaw) * acceleration.x + Math.cos(yaw) * acceleration.z;
        double sideways = Math.cos(yaw) * acceleration.x + Math.sin(yaw) * acceleration.z;
        boolean sprint = forward > 0.0182;
        double speed = sprint ? 0.026 : 0.02;
        brain.move((float) (forward / speed), (float) (sideways / speed), sprint);
    }

    boolean canReach(Vec3 target, Vec3 targetMotion) {
        int ticks = contactTicks(target.y + 0.5);
        double drift = (1 - Math.pow(0.91, ticks)) / 0.09;
        double control = (ticks - 0.91 * drift) / 0.09;
        Vec3 landing = destination(target, targetMotion, ticks);
        Vec3 coast = bot.position().add(bot.getDeltaMovement().multiply(1, 0, 1).scale(drift));
        return landing.subtract(coast).horizontalDistance() <= control * 0.026 + 1.5;
    }

    private Vec3 requiredAcceleration(Vec3 target, Vec3 targetMotion, int ticks) {
        double drift = (1 - Math.pow(0.91, ticks)) / 0.09;
        double control = (ticks - 0.91 * drift) / 0.09;
        Vec3 error = destination(target, targetMotion, ticks).subtract(bot.position()).multiply(1, 0, 1);
        return error.subtract(bot.getDeltaMovement().multiply(1, 0, 1).scale(drift)).scale(1 / control);
    }

    private Vec3 destination(Vec3 target, Vec3 targetMotion, int ticks) {
        Vec3 lead = targetMotion.multiply(1, 0, 1).scale(ticks);
        return new Vec3(Mth.clamp(target.x + lead.x, -37, 37), target.y, Mth.clamp(target.z + lead.z, -37, 37));
    }

    private int contactTicks(double height) {
        double y = bot.getY();
        double velocity = bot.getDeltaMovement().y;
        for (int tick = 1; tick <= 70; tick++) {
            y += velocity;
            velocity = (velocity - 0.08) * 0.98;
            if (velocity < 0 && y <= height) {
                return tick;
            }
        }
        return 70;
    }
}
