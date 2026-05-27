package org.developerkubilay.safra.client.config;

import cpw.mods.fml.common.Loader;

import java.io.File;
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
        return new File(Loader.instance().getConfigDir(), FILE_NAME).toPath();
    }
}
