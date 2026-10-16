package dev.koefi.boxpvp.combat;

import dev.koefi.boxpvp.mixin.ItemCooldownsAccessor;
import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;

public final class Cooldowns {
    private Cooldowns() {
    }

    public static void clear(ServerPlayer player) {
        var accessor = (ItemCooldownsAccessor) player.getCooldowns();
        new ArrayList<>(accessor.boxpvp$cooldowns().keySet()).forEach(player.getCooldowns()::removeCooldown);
    }
}
