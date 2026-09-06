package org.developerkubilay.safra.client.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class SafraClientConfig extends BaseSafraClientConfig {
    private static final String FILE_NAME = "safra-client.json";

    private static SafraClientConfig instance;
    private SafraClientConfig() {
    }

    public static synchronized SafraClientConfig get() {
        if (instance == null) {
            instance = load(new SafraClientConfig());
        }
        return instance;
    }

    @Override
    protected Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
