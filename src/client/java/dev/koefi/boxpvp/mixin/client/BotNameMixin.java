package dev.koefi.boxpvp.mixin.client;

import dev.koefi.boxpvp.arena.Arena;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class BotNameMixin {
    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void boxpvp$healthName(Entity entity, CallbackInfoReturnable<Component> callback) {
        if (entity instanceof Player player && Arena.inside(player) && player.getGameProfile().name().startsWith("PvP_Bot_")) {
            String number = player.getGameProfile().name().substring(8);
            String hp = String.format(Locale.ROOT, "Бот %s • %.1f / %.0f HP", number, player.getHealth(), player.getMaxHealth());
            if (player.getAbsorptionAmount() > 0) {
                hp += String.format(Locale.ROOT, " +%.1f", player.getAbsorptionAmount());
            }
            callback.setReturnValue(Component.literal(hp).withStyle(player.getHealth() > 10 ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
    }
}
