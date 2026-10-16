package dev.koefi.boxpvp.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.koefi.boxpvp.BoxPvp;
import dev.koefi.boxpvp.arena.Arena;
import dev.koefi.boxpvp.bot.BotPlayer;
import dev.koefi.boxpvp.kit.KitDefinition;
import dev.koefi.boxpvp.kit.KitRepository;
import dev.koefi.boxpvp.menu.MenuState;
import dev.koefi.boxpvp.menu.TrainingMenu;
import dev.koefi.boxpvp.storage.PlayerSnapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class TrainingService {
    private static final Map<MinecraftServer, TrainingService> SERVICES = new HashMap<>();
    private final MinecraftServer server;
    private final Map<UUID, MatchSession> sessions = new HashMap<>();
    private final Map<UUID, KitRepository> repositories = new HashMap<>();
    private final Map<UUID, Integer> recovering = new HashMap<>();
    private final Map<UUID, MenuState> menuStates = new HashMap<>();
    private Arena.Preparation preparation;
    private PendingStart pendingStart;
    private final Map<UUID, PendingSetting> pendingSettings = new HashMap<>();

    private record PendingSetting(int field, int expires) {
    }

    private record PendingStart(ServerPlayer player, MatchSettings settings, KitDefinition kit) {
    }

    private TrainingService(MinecraftServer server) {
        this.server = server;
    }

    public static TrainingService get(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, TrainingService::new);
    }

    public static MatchSession session(Entity entity) {
        if (entity instanceof BotPlayer bot) {
            return bot.session;
        }
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        TrainingService service = SERVICES.get(level.getServer());
        return service == null ? null : service.sessions.get(entity.getUUID());
    }

    public static boolean allowHit(Player attacker, Entity target, boolean critical) {
        MatchSession session = session(attacker);
        if (session == null || session(target) != session) {
            return true;
        }
        if (!session.active() || attacker instanceof BotPlayer && target instanceof BotPlayer) {
            return false;
        }
        return session.settings.rule().accepts(critical);
    }

    public static boolean allowDamage(LivingEntity entity, DamageSource source) {
        MatchSession session = session(entity);
        if (session == null) {
            return true;
        }
        if (!session.active() || entity instanceof BotPlayer bot && bot.defeated) {
            return false;
        }
        return !(entity instanceof BotPlayer && source.getEntity() instanceof BotPlayer);
    }

    public static boolean allowDeath(LivingEntity entity) {
        MatchSession session = session(entity);
        if (session == null) {
            return true;
        }
        entity.setHealth(1);
        if (entity instanceof BotPlayer bot) {
            bot.defeated = true;
        } else {
            session.finish("Поражение");
        }
        return false;
    }

    private boolean accepts(ServerPlayer player) {
        return server.isSingleplayer() && server.getSingleplayerProfile() != null
            && server.getSingleplayerProfile().id().equals(player.getUUID()) && !recovering.containsKey(player.getUUID());
    }

    public void joined(ServerPlayer player) {
        if (server.isSingleplayer() && !(player instanceof BotPlayer)) {
            recovering.put(player.getUUID(), 10);
        }
    }

    public void tick() {
        if (server.isPaused()) {
            return;
        }
        for (UUID uuid : new ArrayList<>(pendingSettings.keySet())) {
            if (server.getTickCount() >= pendingSettings.get(uuid).expires()) {
                pendingSettings.remove(uuid);
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) {
                    open(player, "Время ввода истекло. Настройка не изменена.");
                }
            }
        }
        if (preparation != null) {
            try {
                preparation.tick();
                if (pendingStart != null) {
                    if (preparation.ready()) {
                        PendingStart pending = pendingStart;
                        pendingStart = null;
                        try {
                            start(pending.player(), pending.settings(), pending.kit());
                        } catch (Exception exception) {
                            open(pending.player(), exception.getMessage() == null ? "Не удалось начать бой" : exception.getMessage());
                        }
                    } else if (server.getTickCount() % 5 == 0 && pendingStart.player().containerMenu instanceof TrainingMenu menu) {
                        menu.draw();
                    }
                }
            } catch (Exception exception) {
                if (pendingStart != null) {
                    ServerPlayer player = pendingStart.player();
                    pendingStart = null;
                    open(player, "Не удалось подготовить арену: " + exception.getMessage());
                }
                BoxPvp.LOGGER.error("Arena preparation failed", exception);
                preparation.close();
                preparation = null;
            }
        }
        for (UUID uuid : new ArrayList<>(recovering.keySet())) {
            int delay = recovering.get(uuid) - 1;
            if (delay > 0) {
                recovering.put(uuid, delay);
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                try {
                    PlayerSnapshot snapshot = PlayerSnapshot.pending(player);
                    if (snapshot != null) {
                        Arena.cleanupAll(server);
                        snapshot.restore(player);
                        player.sendSystemMessage(Component.literal("Незавершённая тренировка закрыта. Исходное состояние восстановлено."));
                    }
                    recovering.remove(uuid);
                } catch (Exception exception) {
                    BoxPvp.LOGGER.error("Player recovery failed", exception);
                    player.sendSystemMessage(Component.literal("Не удалось восстановить состояние: " + exception.getMessage() + ". Снимок сохранён; повтор через 5 секунд."));
                    recovering.put(uuid, 100);
                }
            } else {
                recovering.remove(uuid);
            }
        }
        for (MatchSession session : new ArrayList<>(sessions.values())) {
            try {
                session.tick();
            } catch (Exception exception) {
                BoxPvp.LOGGER.error("Match tick failed", exception);
                session.finish("Бой остановлен из-за ошибки");
            }
        }
    }

    public KitRepository kits(ServerPlayer player) throws IOException {
        KitRepository repository = repositories.get(player.getUUID());
        if (repository == null) {
            repository = new KitRepository(player);
            repositories.put(player.getUUID(), repository);
        }
        return repository;
    }

    public void open(ServerPlayer player, String error) {
        if (!accepts(player)) {
            player.sendSystemMessage(Component.literal("PvP-тренажёр доступен владельцу одиночного мира после загрузки."));
            return;
        }
        try {
            pendingSettings.remove(player.getUUID());
            if (preparation == null) {
                preparation = new Arena.Preparation(server);
            }
            MenuState state = menuStates.computeIfAbsent(player.getUUID(), uuid -> new MenuState());
            state.message = error;
            if (player.containerMenu instanceof TrainingMenu menu) {
                menu.draw();
            } else {
                player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, opener) -> new TrainingMenu(id, inventory, this, state), Component.literal("BoxPVP • Тренировка")));
            }
        } catch (Exception exception) {
            BoxPvp.LOGGER.error("Failed to open training menu", exception);
            player.sendSystemMessage(Component.literal("Меню недоступно: " + exception.getMessage()));
        }
    }

    public void closeView(ServerPlayer player) {
        player.closeContainer();
    }

    public void requestSetting(ServerPlayer player, int field) {
        if (!accepts(player) || sessions.containsKey(player.getUUID()) || preparing(player) || field < 0 || field > 7) {
            return;
        }
        pendingSettings.put(player.getUUID(), new PendingSetting(field, server.getTickCount() + 1200));
        player.closeContainer();
        player.sendSystemMessage(Component.literal("Введи значение в чат: " + CombatTuning.range(field) + ". Для отмены: отмена. Ввод не отправится в общий чат."));
    }

    public static boolean receiveChat(ServerPlayer player, String text) {
        TrainingService service = SERVICES.get(player.level().getServer());
        if (service == null || !service.pendingSettings.containsKey(player.getUUID())) {
            return true;
        }
        service.submitSetting(player, text);
        return false;
    }

    private void submitSetting(ServerPlayer player, String text) {
        PendingSetting pending = pendingSettings.get(player.getUUID());
        if (text.trim().equalsIgnoreCase("отмена") || text.trim().equalsIgnoreCase("cancel")) {
            open(player, "Ввод отменён");
            return;
        }
        try {
            MenuState state = menuStates.computeIfAbsent(player.getUUID(), uuid -> new MenuState());
            state.tuning = state.tuning.withValue(pending.field(), text);
            open(player, "Значение сохранено: " + state.tuning.value(pending.field()));
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(Component.literal(exception.getMessage() + ". Допустимо: " + CombatTuning.range(pending.field())));
        }
    }

    public boolean preparing(ServerPlayer player) {
        return pendingStart != null && pendingStart.player().getUUID().equals(player.getUUID());
    }

    public int preparationPercent() {
        return preparation == null ? 0 : preparation.percent();
    }

    public boolean arenaReady() {
        return preparation != null && preparation.ready();
    }

    private void start(ServerPlayer player, MatchSettings settings, KitDefinition kit) throws IOException {
        if (player.isRemoved() || player.isDeadOrDying() || player.isPassenger() || player.isSleeping() || !player.onGround()
            || !player.getEnderPearls().isEmpty() || player.containerMenu != player.inventoryMenu && !(player.containerMenu instanceof TrainingMenu)) {
            throw new IllegalArgumentException("Начни тренировку стоя на земле, вне транспорта и контейнеров");
        }
        player.closeContainer();
        MatchSession session = new MatchSession(this, player, settings, kit);
        sessions.put(player.getUUID(), session);
        try {
            session.begin();
        } catch (Exception exception) {
            leave(player);
            throw exception;
        }
    }

    public void action(ServerPlayer player, String json) {
        if (!accepts(player)) {
            return;
        }
        try {
            JsonObject data = JsonParser.parseString(json).getAsJsonObject();
            String action = data.get("action").getAsString();
            MatchSession session = sessions.get(player.getUUID());
            switch (action) {
                case "open" -> open(player, "");
                case "leave" -> leave(player);
                case "repeat" -> {
                    if (session == null || !session.finished()) {
                        throw new IllegalArgumentException("Предыдущий бой ещё не завершён");
                    }
                    session.begin();
                }
                case "start" -> {
                    if (session != null || pendingStart != null || PlayerSnapshot.pending(player) != null) {
                        throw new IllegalArgumentException("Сначала заверши текущую тренировку");
                    }
                    if (player.isDeadOrDying() || player.isPassenger() || player.isSleeping() || !player.onGround() || !player.getEnderPearls().isEmpty() || player.containerMenu != player.inventoryMenu && !(player.containerMenu instanceof TrainingMenu)) {
                        throw new IllegalArgumentException("Начни тренировку стоя на земле, вне транспорта и контейнеров");
                    }
                    KitDefinition kit = kits(player).get(data.get("kit").getAsString());
                    if (data.has("protection")) {
                        int protection = data.get("protection").getAsInt();
                        if (protection < -1 || protection > 4) {
                            throw new IllegalArgumentException("Некорректный уровень защиты");
                        }
                        if (protection >= 0) {
                            kit = kits(player).withProtection(kit, protection);
                        }
                    }
                    CombatRule rule = kit.custom() ? CombatRule.valueOf(data.get("rule").getAsString()) : kit.rule();
                    DifficultyProfile difficulty = DifficultyProfile.valueOf(data.get("difficulty").getAsString());
                    CombatTuning tuning = data.has("tuning") ? new com.google.gson.Gson().fromJson(data.get("tuning"), CombatTuning.class) : CombatTuning.of(difficulty);
                    MatchSettings settings = new MatchSettings(difficulty, data.get("count").getAsInt(), kit.id(), rule, tuning,
                        data.has("autoReplay") && data.get("autoReplay").getAsBoolean());
                    if (preparation == null) {
                        preparation = new Arena.Preparation(server);
                    }
                    if (preparation.ready()) {
                        start(player, settings, kit);
                    } else {
                        pendingStart = new PendingStart(player, settings, kit);
                        open(player, "");
                    }
                }
                case "save" -> {
                    if (session != null || Arena.inside(player)) {
                        throw new IllegalArgumentException("Сохранять набор можно только вне тренировки");
                    }
                    kits(player).saveCurrent(data.get("name").getAsString());
                    open(player, "Набор сохранён");
                }
                case "delete" -> {
                    if (session != null) {
                        throw new IllegalArgumentException("Сначала заверши тренировку");
                    }
                    kits(player).delete(data.get("kit").getAsString());
                    menuStates.computeIfAbsent(player.getUUID(), uuid -> new MenuState()).kit = "op";
                    open(player, "Набор удалён");
                }
                default -> throw new IllegalArgumentException("Неизвестное действие");
            }
        } catch (Exception exception) {
            BoxPvp.LOGGER.warn("Training action rejected: {}", exception.toString());
            open(player, exception.getMessage() == null ? "Ошибка действия" : exception.getMessage());
        }
    }

    public void leave(ServerPlayer player) throws IOException {
        if (preparing(player)) {
            pendingStart = null;
        }
        MatchSession session = sessions.get(player.getUUID());
        if (session != null) {
            session.leave();
            sessions.remove(player.getUUID());
        } else {
            PlayerSnapshot pending = PlayerSnapshot.pending(player);
            if (pending != null) {
                pending.restore(player);
            }
        }
        closeView(player);
    }

    public void disconnected(ServerPlayer player) {
        pendingSettings.remove(player.getUUID());
        if (preparing(player)) {
            pendingStart = null;
        }
        MatchSession session = sessions.remove(player.getUUID());
        if (session != null) {
            session.suspend();
        }
        recovering.remove(player.getUUID());
        repositories.remove(player.getUUID());
        menuStates.remove(player.getUUID());
    }

    public static void disconnect(MinecraftServer server, ServerPlayer player) {
        TrainingService service = SERVICES.get(server);
        if (service != null) {
            service.disconnected(player);
        }
    }

    public static void stop(MinecraftServer server) {
        TrainingService service = SERVICES.remove(server);
        if (service != null) {
            service.pendingStart = null;
            if (service.preparation != null) {
                service.preparation.close();
            }
            for (MatchSession session : new ArrayList<>(service.sessions.values())) {
                try {
                    if (session.owner.isRemoved()) {
                        session.suspend();
                    } else {
                        session.leave();
                    }
                } catch (Exception exception) {
                    BoxPvp.LOGGER.error("Recovery saved for next login", exception);
                }
            }
        }
    }
}
