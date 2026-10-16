package dev.koefi.boxpvp;

import net.fabricmc.api.ModInitializer;
import dev.koefi.boxpvp.arena.Arena;
import dev.koefi.boxpvp.combat.TrainingService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.Commands;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BoxPvp implements ModInitializer {
    public static final String MOD_ID = "boxpvp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Arena.initialize();
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, parameters) -> TrainingService.receiveChat(sender, message.signedContent()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(Commands.literal("bot")
            .executes(context -> {
                var player = context.getSource().getPlayerOrException();
                TrainingService.get(context.getSource().getServer()).open(player, "");
                return 1;
            })
            .then(Commands.literal("kit").then(Commands.literal("save").then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(context -> {
                var json = new com.google.gson.JsonObject();
                json.addProperty("action", "save");
                json.addProperty("name", com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name"));
                TrainingService.get(context.getSource().getServer()).action(context.getSource().getPlayerOrException(), json.toString());
                return 1;
            }))))
            .then(Commands.literal("leave").executes(context -> {
                try {
                    TrainingService.get(context.getSource().getServer()).leave(context.getSource().getPlayerOrException());
                } catch (java.io.IOException exception) {
                    context.getSource().sendFailure(net.minecraft.network.chat.Component.literal("Не удалось восстановить состояние: " + exception.getMessage()));
                }
                return 1;
            }))));
        ServerTickEvents.END_SERVER_TICK.register(server -> TrainingService.get(server).tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(TrainingService::stop);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> TrainingService.get(server).joined(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> TrainingService.disconnect(server, handler.player)));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> TrainingService.allowDamage(entity, source));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> TrainingService.allowDeath(entity));
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> Arena.inside(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> Arena.useBlock(player, hand));
        LOGGER.info("BoxPVP initialized");
    }
}
