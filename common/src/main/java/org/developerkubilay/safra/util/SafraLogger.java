package org.developerkubilay.safra.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SafraLogger {

    private SafraLogger() {}

    public static Logger get(Class<?> clazz) {
        return LogManager.getLogger("safra." + clazz.getSimpleName());
    }

    public static Logger get(String name) {
        return LogManager.getLogger("safra." + name);
    }
}
