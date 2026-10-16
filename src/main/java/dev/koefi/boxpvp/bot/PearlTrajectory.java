package dev.koefi.boxpvp.bot;

import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class PearlTrajectory {
    private PearlTrajectory() {
    }

    public static Vec3 landing(ThrownEnderpearl pearl) {
        Vec3 position = pearl.position();
        Vec3 velocity = pearl.getDeltaMovement();
        for (int tick = 0; tick < 160; tick++) {
            velocity = velocity.add(0, -0.03, 0).scale(0.99);
            Vec3 next = position.add(velocity);
            HitResult hit = pearl.level().clip(new ClipContext(position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pearl));
            if (hit.getType() != HitResult.Type.MISS) {
                return hit.getLocation();
            }
            position = next;
            if (position.y < 64) {
                return position;
            }
        }
        return position;
    }

    public static float pitch(BotPlayer bot, Vec3 goal, float yaw) {
        float best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (float pitch = -75; pitch <= 60; pitch += 1) {
            Vec3 position = bot.getEyePosition().add(0, -0.1, 0);
            Vec3 velocity = Vec3.directionFromRotation(pitch, yaw).scale(1.5).add(bot.getKnownMovement().multiply(1, bot.onGround() ? 0 : 1, 1));
            for (int tick = 0; tick < 140; tick++) {
                velocity = velocity.add(0, -0.03, 0).scale(0.99);
                Vec3 next = position.add(velocity);
                if (next.y <= 65 || Math.abs(next.x) >= 39.4 || Math.abs(next.z) >= 39.4 || next.y >= 128.4) {
                    double distance = next.distanceToSqr(goal) + tick * 0.12;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pitch;
                    }
                    break;
                }
                position = next;
            }
        }
        return best;
    }
}
