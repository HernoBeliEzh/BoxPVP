package dev.koefi.boxpvp.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class WorldFiles {
    private WorldFiles() {
    }

    public static Path directory(MinecraftServer server) throws IOException {
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("boxpvp");
        Path legacy = server.getWorldPath(LevelResource.ROOT).resolve("fabricbase");
        if (Files.isDirectory(legacy)) {
            directory = legacy;
        }
        Files.createDirectories(directory);
        return directory;
    }

    public static CompoundTag read(Path path) throws IOException {
        return NbtIo.readCompressed(path, NbtAccounter.create(16L * 1024 * 1024));
    }

    public static void write(Path path, CompoundTag tag) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temporary);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
