package dev.koefi.boxpvp.arena;

import dev.koefi.boxpvp.bot.BotPlayer;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

public final class Arena {
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("boxpvp", "arena"));
    public static final int FLOOR = 64;
    public static final double MIN = -39.4;
    public static final double MAX = 39.4;
    private static final ResourceKey<Level> LEGACY_KEY = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("fabricbase", "arena"));
    private static final BlockPos MARKER = new BlockPos(0, FLOOR - 2, 0);
    private static final TicketType WARMUP = new TicketType(0, TicketType.FLAG_LOADING);
    private static final int WIDTH = 112;

    private Arena() {
    }

    public static void initialize() {
        Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("boxpvp", "arena_warmup"), WARMUP);
    }

    private static ServerLevel level(MinecraftServer server) {
        ServerLevel level = server.getLevel(KEY);
        if (level == null) {
            throw new IllegalStateException("Измерение арены не загрузилось. Перезапусти мир с включённым модом.");
        }
        return level;
    }

    public static ServerLevel prepare(MinecraftServer server) {
        ServerLevel level = level(server);
        if (!level.getBlockState(MARKER).is(Blocks.CHISELED_COPPER)) {
            throw new IllegalStateException("Арена ещё подготавливается");
        }
        return level;
    }

    public static InteractionResult useBlock(Player player, InteractionHand hand) {
        if (!inside(player)) {
            return InteractionResult.PASS;
        }
        var stack = player.getItemInHand(hand);
        return stack.isEmpty() || stack.getItem() instanceof ProjectileItem || stack.is(Items.ENDER_PEARL)
            || stack.is(Items.POTION) || stack.is(Items.SHIELD) || stack.has(DataComponents.FOOD)
            || stack.is(Items.MACE) || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS)
            ? InteractionResult.PASS : InteractionResult.FAIL;
    }

    public static final class Preparation implements AutoCloseable {
        private final ServerLevel level;
        private final CompletableFuture<?> chunks;
        private int columns;
        private boolean checked;
        private boolean ready;

        public Preparation(MinecraftServer server) {
            level = level(server);
            chunks = level.getChunkSource().addTicketAndLoadWithRadius(WARMUP, new ChunkPos(0, 0), 4);
        }

        public void tick() {
            if (ready || !chunks.isDone()) {
                return;
            }
            chunks.join();
            if (!checked) {
                checked = true;
                if (level.getBlockState(MARKER).is(Blocks.CHISELED_COPPER)) {
                    ready = true;
                    return;
                }
            }
            long deadline = System.nanoTime() + 3_000_000;
            int limit = Math.min(WIDTH * WIDTH, columns + 96);
            do {
                int x = columns / WIDTH - WIDTH / 2;
                int z = columns % WIDTH - WIDTH / 2;
                if (x < -41 || x > 40 || z < -41 || z > 40) {
                    landscape(level, x, z);
                    columns++;
                    continue;
                }
                boolean edge = x == -41 || x == 40 || z == -41 || z == 40;
                level.setBlock(new BlockPos(x, FLOOR - 1, z), Blocks.BEDROCK.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, FLOOR, z), floor(x, z).defaultBlockState(), 2);
                if (edge) {
                    boolean pillar = Math.floorMod(x == -41 || x == 40 ? z + 1 : x + 1, 10) == 0 || (x == -41 || x == 40) && (z == -41 || z == 40);
                    for (int y = FLOOR + 1; y <= FLOOR + 64; y++) {
                        Block block = y <= FLOOR + 2 ? Blocks.POLISHED_BLACKSTONE_BRICKS
                            : y == FLOOR + 9 ? pillar ? Blocks.SEA_LANTERN : Blocks.DARK_PRISMARINE
                            : y < FLOOR + 9 ? pillar ? Blocks.POLISHED_BASALT : Blocks.CYAN_STAINED_GLASS
                            : Blocks.BARRIER;
                        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
                    }
                } else {
                    for (int y = 1; y <= 8; y++) {
                        level.setBlock(new BlockPos(x, FLOOR + y, z), ruin(x, y, z).defaultBlockState(), 2);
                    }
                }
                level.setBlock(new BlockPos(x, FLOOR + 65, z), Blocks.BARRIER.defaultBlockState(), 2);
                columns++;
            } while (columns < limit && System.nanoTime() < deadline);
            if (columns == WIDTH * WIDTH) {
                level.setBlock(MARKER, Blocks.CHISELED_COPPER.defaultBlockState(), 2);
                ready = true;
            }
        }

        public boolean ready() {
            return ready;
        }

        public int percent() {
            return ready ? 100 : chunks.isDone() ? 20 + columns * 80 / (WIDTH * WIDTH) : 0;
        }

        @Override
        public void close() {
            level.getChunkSource().removeTicketWithRadius(WARMUP, new ChunkPos(0, 0), 4);
        }
    }

    private static void landscape(ServerLevel level, int x, int z) {
        double hills = Math.sin(x * 0.15) * Math.cos(z * 0.12) * 3 + Math.sin((x + z) * 0.07) * 4;
        int top = FLOOR - 1 + (int) Math.round(hills);
        int bottom = Math.min(FLOOR - 9, top - 3);
        for (int y = bottom; y <= top; y++) {
            Block block = y == top ? Math.floorMod(x * 17 + z * 31, 11) < 3 ? Blocks.MOSS_BLOCK : Blocks.GRASS_BLOCK
                : y >= top - 2 ? Blocks.DIRT : Blocks.STONE;
            level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
        }
        if (Math.floorMod(x * 71 + z * 37, 97) == 0) {
            level.setBlock(new BlockPos(x, top + 1, z), Blocks.FLOWERING_AZALEA.defaultBlockState(), 2);
        }
        if (Math.floorMod(x + 2, 17) == 0 && Math.floorMod(z + 3, 17) == 0) {
            for (int y = 1; y <= 5; y++) {
                level.setBlock(new BlockPos(x, top + y, z), (y == 5 ? Blocks.SEA_LANTERN : Blocks.MOSSY_STONE_BRICKS).defaultBlockState(), 2);
            }
        }
    }

    private static Block ruin(int x, int y, int z) {
        for (int cx : new int[]{-28, 28}) {
            for (int cz : new int[]{-28, 28}) {
                int dx = x - cx;
                int dz = z - cz;
                if (Math.abs(dx) > 5 || Math.abs(dz) > 3) {
                    continue;
                }
                boolean pillar = Math.abs(dx) >= 3 && Math.abs(dx) <= 4 && dz >= 0 && dz <= 1;
                if (pillar && y <= 5) {
                    return y == 1 ? Blocks.CHISELED_STONE_BRICKS : Blocks.POLISHED_BASALT;
                }
                if (Math.abs(dx) <= 4 && dz >= 0 && dz <= 1 && (y == 6 || y == 5 && Math.abs(dx) >= 2)) {
                    return Math.abs(dx) == 4 && y == 6 ? Blocks.SEA_LANTERN : Blocks.MOSSY_STONE_BRICKS;
                }
                if (dx >= -5 && dx <= -2 && dz == -3 && y <= Math.max(1, 3 - Math.abs(dx + 3))) {
                    return Blocks.CRACKED_STONE_BRICKS;
                }
            }
        }
        return Blocks.AIR;
    }

    private static Block floor(int x, int z) {
        double dx = x + 0.5;
        double dz = z + 0.5;
        double radius = Math.sqrt(dx * dx + dz * dz);
        double edge = Math.max(Math.abs(dx), Math.abs(dz));
        if (edge > 37) {
            return Math.floorMod(x + z, 10) == 0 ? Blocks.SEA_LANTERN : Blocks.POLISHED_BLACKSTONE_BRICKS;
        }
        if (Math.abs(radius - 12) < 0.65 || Math.abs(radius - 27) < 0.65 || Math.abs(edge - 35) < 0.6) {
            return Blocks.CYAN_CONCRETE;
        }
        if (Math.abs(dx) < 1 || Math.abs(dz) < 1) {
            return Math.floorMod(x + z, 8) == 0 ? Blocks.SEA_LANTERN : Blocks.QUARTZ_BRICKS;
        }
        if (radius < 4) {
            return Math.abs(dx) + Math.abs(dz) < 3 ? Blocks.SEA_LANTERN : Blocks.DARK_PRISMARINE;
        }
        if (Math.abs(Math.abs(dx) - Math.abs(dz)) < 0.65) {
            return Blocks.POLISHED_DEEPSLATE;
        }
        return Math.floorMod(Math.floorDiv(x, 5) + Math.floorDiv(z, 5), 2) == 0 ? Blocks.DEEPSLATE_TILES : Blocks.DEEPSLATE_BRICKS;
    }

    public static void confine(net.minecraft.server.level.ServerPlayer player, ServerLevel arena) {
        if (player.level() != arena) {
            player.teleportTo(arena, 0.5, FLOOR + 1, -14.5, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            return;
        }
        double x = Mth.clamp(player.getX(), MIN, MAX);
        double y = Mth.clamp(player.getY(), FLOOR + 1, FLOOR + 62);
        double z = Mth.clamp(player.getZ(), MIN, MAX);
        if (x == player.getX() && y == player.getY() && z == player.getZ()) {
            return;
        }
        Vec3 previous = player.position();
        if (player instanceof BotPlayer) {
            player.snapTo(x, y, z, player.getYRot(), player.getXRot());
        } else {
            player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
        }
        Vec3 movement = player.getDeltaMovement();
        player.setDeltaMovement(new Vec3(x == previous.x ? movement.x : 0, y == previous.y ? movement.y : 0, z == previous.z ? movement.z : 0));
        if (y <= FLOOR + 1 || y >= FLOOR + 62) {
            player.resetFallDistance();
        }
    }

    public static void cleanupAll(MinecraftServer server) {
        for (ResourceKey<Level> key : java.util.List.of(KEY, LEGACY_KEY)) {
            ServerLevel level = server.getLevel(key);
            if (level != null) {
                cleanup(level);
            }
        }
    }

    public static void cleanup(ServerLevel level) {
        ArrayList<Entity> entities = new ArrayList<>();
        level.getAllEntities().forEach(entities::add);
        for (Entity entity : entities) {
            if (entity instanceof BotPlayer bot) {
                bot.removeBot();
            } else if (!(entity instanceof net.minecraft.server.level.ServerPlayer)) {
                entity.discard();
            }
        }
    }

    public static boolean inside(Entity entity) {
        return entity.level().dimension().equals(KEY) || entity.level().dimension().equals(LEGACY_KEY);
    }

    public static void removeMonsters(ServerLevel level) {
        for (Entity entity : level.getEntitiesOfClass(Monster.class, new net.minecraft.world.phys.AABB(-45, 0, -45, 45, 256, 45))) {
            entity.discard();
        }
    }
}
