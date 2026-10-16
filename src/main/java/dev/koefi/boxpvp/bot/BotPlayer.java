package dev.koefi.boxpvp.bot;

import com.mojang.authlib.GameProfile;
import dev.koefi.boxpvp.combat.MatchSession;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public final class BotPlayer extends ServerPlayer {
    public final MatchSession session;
    public final BotBrain brain;
    public boolean defeated;
    private Vec3 lastMotion = Vec3.ZERO;

    public BotPlayer(MatchSession session, int index, double x, double z) {
        super(session.owner.level().getServer(), session.arena, new GameProfile(UUID.randomUUID(), "PvP_Bot_" + (index + 1)), ClientInformation.createDefault());
        this.session = session;
        brain = new BotBrain(this, index);
        new ServerGamePacketListenerImpl(level().getServer(), new BotConnection(), this, CommonListenerCookie.createInitial(getGameProfile(), false));
        gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        snapTo(x, 65, z, 180, 0);
        session.kit.equip(this);
        setHealth(getMaxHealth());
        session.owner.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(this)));
        level().addNewPlayer(this);
    }

    @Override
    public boolean isClientAuthoritative() {
        return false;
    }

    @Override
    public void tick() {
        if (defeated) {
            return;
        }
        super.tick();
        Vec3 previous = position();
        doTick();
        lastMotion = position().subtract(previous);
    }

    @Override
    protected void serverAiStep() {
        brain.tick();
    }

    @Override
    public Vec3 getKnownMovement() {
        return lastMotion;
    }

    @Override
    public Vec3 getKnownSpeed() {
        return lastMotion;
    }

    @Override
    public boolean canHarmPlayer(net.minecraft.world.entity.player.Player player) {
        return player == session.owner;
    }

    @Override
    public boolean isPushable() {
        return !defeated && super.isPushable();
    }

    public void removeBot() {
        if (!isRemoved()) {
            stopUsingItem();
            level().removePlayerImmediately(this, Entity.RemovalReason.DISCARDED);
            session.owner.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(getUUID())));
        }
    }
}
