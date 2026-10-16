package dev.koefi.boxpvp.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public record PlayerSnapshot(Path path, CompoundTag data) {
    public static Path path(ServerPlayer player) throws IOException {
        return WorldFiles.directory(player.level().getServer()).resolve("recovery-" + player.getUUID() + ".nbt");
    }

    public static PlayerSnapshot capture(ServerPlayer player) throws IOException {
        Path path = path(player);
        if (Files.exists(path)) {
            throw new IOException("Сначала нужно восстановить предыдущую тренировку");
        }
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(output);
        CompoundTag data = output.buildResult();
        WorldFiles.write(path, data);
        return new PlayerSnapshot(path, data);
    }

    public static PlayerSnapshot pending(ServerPlayer player) throws IOException {
        Path path = path(player);
        return Files.exists(path) ? new PlayerSnapshot(path, WorldFiles.read(path)) : null;
    }

    public void restore(ServerPlayer player) throws IOException {
        Identifier dimension = Identifier.parse(data.getStringOr("Dimension", "minecraft:overworld"));
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimension);
        ServerLevel destination = player.level().getServer().getLevel(key);
        if (destination == null) {
            throw new IOException("Исходное измерение недоступно: " + dimension);
        }
        player.stopUsingItem();
        player.removeAllEffects();
        player.getInventory().clearContent();
        var position = data.getListOrEmpty("Pos");
        double x = position.getDoubleOr(0, 0);
        double y = position.getDoubleOr(1, 80);
        double z = position.getDoubleOr(2, 0);
        player.teleportTo(destination, x, y, z, Set.<Relative>of(), 0, 0, false);
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), data.copy()));
        dev.koefi.boxpvp.combat.Cooldowns.clear(player);
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        player.onUpdateAbilities();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(net.minecraft.network.protocol.game.ClientboundGameEventPacket.CHANGE_GAME_MODE, player.gameMode.getGameModeForPlayer().getId()));
        player.inventoryMenu.sendAllDataToRemote();
        destination.getServer().getPlayerList().sendActivePlayerEffects(player);
        destination.getServer().getPlayerList().sendAllPlayerInfo(player);
        destination.getServer().saveEverything(true, true, true);
        CompoundTag saved = WorldFiles.read(destination.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("level.dat")).getCompoundOrEmpty("Data").getCompoundOrEmpty("Player");
        for (String keyName : new String[]{"Inventory", "equipment", "XpTotal", "Dimension"}) {
            if (!java.util.Objects.equals(saved.get(keyName), data.get(keyName))) {
                throw new IOException("Не подтверждено сохранение восстановленного состояния: " + keyName);
            }
        }
        Files.deleteIfExists(path);
    }
}
