package dev.koefi.boxpvp.client;

import dev.koefi.boxpvp.BoxPvp;
import net.fabricmc.api.ClientModInitializer;

public final class BoxPvpClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BoxPvp.LOGGER.info("BoxPVP client initialized");
    }
}
