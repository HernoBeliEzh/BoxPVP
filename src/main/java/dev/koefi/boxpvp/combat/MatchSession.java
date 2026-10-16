package dev.koefi.boxpvp.combat;

import dev.koefi.boxpvp.arena.Arena;
import dev.koefi.boxpvp.bot.BotPlayer;
import dev.koefi.boxpvp.kit.KitDefinition;
import dev.koefi.boxpvp.storage.PlayerSnapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public final class MatchSession {
    public final ServerPlayer owner;
    public final ServerLevel arena;
    public final MatchSettings settings;
    public final KitDefinition kit;
    public final PlayerSnapshot snapshot;
    public final List<BotPlayer> bots = new ArrayList<>();
    public long pearlSequence;
    public Vec3 pearlLanding;
    private final java.util.Set<java.util.UUID> observedPearls = new java.util.HashSet<>();
    private final TrainingService service;
    private final ServerBossEvent bar = new ServerBossEvent(Component.literal("Подготовка"), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
    private int countdown;
    private int elapsed;
    private String result = "";
    private boolean closed;
    private boolean resultSent;
    private int replayCountdown;
    private int wins;
    private int losses;

    public MatchSession(TrainingService service, ServerPlayer owner, MatchSettings settings, KitDefinition kit) throws IOException {
        this.service = service;
        this.owner = owner;
        this.settings = settings;
        this.kit = kit;
        arena = Arena.prepare(owner.level().getServer());
        snapshot = PlayerSnapshot.capture(owner);
    }

    public void begin() {
        Arena.cleanup(arena);
        bots.clear();
        observedPearls.clear();
        pearlSequence = 0;
        pearlLanding = null;
        owner.closeContainer();
        owner.stopRiding();
        owner.stopUsingItem();
        owner.removeAllEffects();
        owner.setGameMode(GameType.SURVIVAL);
        owner.getAbilities().flying = false;
        owner.onUpdateAbilities();
        owner.getInventory().clearContent();
        owner.setHealth(owner.getMaxHealth());
        owner.setAbsorptionAmount(0);
        owner.experienceLevel = 0;
        owner.totalExperience = 0;
        owner.experienceProgress = 0;
        owner.setExperiencePoints(0);
        owner.getFoodData().setFoodLevel(20);
        owner.getFoodData().setSaturation(5);
        owner.setRemainingFireTicks(0);
        owner.resetFallDistance();
        owner.setDeltaMovement(Vec3.ZERO);
        Cooldowns.clear(owner);
        owner.teleportTo(arena, 0.5, Arena.FLOOR + 1, -14.5, Set.<Relative>of(), 0, 0, false);
        kit.equip(owner);
        countdown = 60;
        elapsed = 0;
        result = "";
        resultSent = false;
        replayCountdown = 0;
        for (int index = 0; index < settings.count(); index++) {
            double angle = settings.count() == 1 ? 0 : (index - (settings.count() - 1) / 2.0) * 0.18;
            bots.add(new BotPlayer(this, index, 0.5 + Math.sin(angle) * 15, 4.5 + Math.cos(angle) * 8));
        }
        bar.addPlayer(owner);
        bar.setProgress(1);
        owner.connection.send(new ClientboundSetTitlesAnimationPacket(0, 20, 5));
        service.closeView(owner);
    }

    public boolean active() {
        return !closed && countdown <= 0 && result.isEmpty() && !(owner.containerMenu instanceof dev.koefi.boxpvp.menu.TrainingMenu);
    }

    public boolean finished() {
        return !result.isEmpty();
    }

    public String result() {
        return result;
    }

    public int seconds() {
        return elapsed / 20;
    }

    public int remaining() {
        return (int) bots.stream().filter(bot -> !bot.defeated && !bot.isRemoved()).count();
    }

    public int wins() {
        return wins;
    }

    public int losses() {
        return losses;
    }

    public void tick() {
        if (closed) {
            return;
        }
        Arena.confine(owner, arena);
        for (BotPlayer bot : bots) {
            if (bot.defeated) {
                bot.removeBot();
            } else {
                Arena.confine(bot, arena);
            }
        }
        if (finished()) {
            if (!resultSent) {
                resultSent = true;
                service.open(owner, "");
            }
            boolean repeat = settings.autoReplay() && (result.equals("Победа!") || result.equals("Поражение"));
            if (repeat && --replayCountdown <= 0) {
                begin();
            } else if (repeat && replayCountdown % 20 == 0) {
                bar.setName(Component.literal(result + " • Повтор через " + replayCountdown / 20 + " сек. • /bot leave"));
            }
            return;
        }
        if (owner.containerMenu instanceof dev.koefi.boxpvp.menu.TrainingMenu) {
            return;
        }
        if (countdown > 0) {
            if (countdown % 20 == 0) {
                owner.connection.send(new ClientboundSetTitleTextPacket(Component.literal(Integer.toString(countdown / 20))));
            }
            if (owner.position().distanceToSqr(new Vec3(0.5, 65, -14.5)) > 0.04) {
                owner.connection.teleport(0.5, 65, -14.5, owner.getYRot(), owner.getXRot());
            }
            countdown--;
            bar.setName(Component.literal("Бой через " + (countdown / 20 + 1)));
            if (countdown == 0) {
                owner.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Бой!")));
            }
            return;
        }
        elapsed++;
        for (var pearl : owner.getEnderPearls()) {
            if (!pearl.isRemoved() && observedPearls.add(pearl.getUUID())) {
                pearlLanding = dev.koefi.boxpvp.bot.PearlTrajectory.landing(pearl);
                pearlSequence++;
            }
        }
        if (elapsed % 20 == 0) {
            bar.setName(Component.literal("Боты: " + remaining() + "/" + settings.count() + "  •  " + seconds() + " сек.  •  /bot leave"));
            bar.setProgress((float) remaining() / settings.count());
            Arena.removeMonsters(arena);
        }
        if (remaining() == 0) {
            finish("Победа!");
        }
    }

    public void finish(String result) {
        if (!this.result.isEmpty() || closed) {
            return;
        }
        this.result = result;
        replayCountdown = 100;
        if (result.equals("Победа!")) {
            wins++;
        } else if (result.equals("Поражение")) {
            losses++;
        }
        if (settings.autoReplay() && (result.equals("Победа!") || result.equals("Поражение"))) {
            String ratio = losses == 0 ? "∞" : String.format(java.util.Locale.ROOT, "%.2f", (double) wins / losses);
            owner.sendSystemMessage(Component.literal("Победы: " + wins).withStyle(net.minecraft.ChatFormatting.GREEN)
                .append(Component.literal(" / ").withStyle(net.minecraft.ChatFormatting.GRAY))
                .append(Component.literal("Поражения: " + losses).withStyle(net.minecraft.ChatFormatting.RED))
                .append(Component.literal("  КД: " + ratio).withStyle(net.minecraft.ChatFormatting.GOLD)));
        }
        owner.stopUsingItem();
        owner.setRemainingFireTicks(0);
        if (owner.getHealth() <= 0) {
            owner.setHealth(1);
        }
        for (BotPlayer bot : bots) {
            bot.stopUsingItem();
        }
        List<net.minecraft.world.entity.Entity> entities = new ArrayList<>();
        arena.getAllEntities().forEach(entities::add);
        entities.stream().filter(entity -> !(entity instanceof ServerPlayer)).forEach(net.minecraft.world.entity.Entity::discard);
        bar.setName(Component.literal(result));
    }

    public void leave() throws IOException {
        suspend();
        snapshot.restore(owner);
    }

    public void suspend() {
        closed = true;
        bar.removeAllPlayers();
        for (BotPlayer bot : bots) {
            bot.removeBot();
        }
        new ArrayList<>(owner.getEnderPearls()).forEach(net.minecraft.world.entity.Entity::discard);
        Arena.cleanup(arena);
    }
}
