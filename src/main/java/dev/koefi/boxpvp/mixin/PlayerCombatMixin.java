package dev.koefi.boxpvp.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.koefi.boxpvp.combat.TrainingService;
import dev.koefi.boxpvp.bot.BotPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerCombatMixin {
    @WrapOperation(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void boxpvp$keepServerMotion(Entity target, Vec3 movement, Operation<Void> original) {
        if (!(target instanceof BotPlayer)) {
            original.call(target, movement);
        }
    }

    @WrapOperation(method = "attack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean boxpvp$filterHit(Entity target, DamageSource source, float damage, Operation<Boolean> original, @Local(ordinal = 2) boolean critical) {
        return TrainingService.allowHit((Player) (Object) this, target, critical) && original.call(target, source, damage);
    }

    @Inject(method = "stabAttack", at = @At("HEAD"), cancellable = true)
    private void boxpvp$filterSpear(EquipmentSlot slot, Entity target, float damage, boolean hurt, boolean knockback, boolean dismount, CallbackInfoReturnable<Boolean> callback) {
        if (!TrainingService.allowHit((Player) (Object) this, target, false)) {
            callback.setReturnValue(false);
        }
    }
}
