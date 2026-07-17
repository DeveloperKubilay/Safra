package org.developerkubilay.safra.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SafraLogger {
    private SafraLogger() {
    }

    public static Logger get(Class<?> clazz) {
        return LoggerFactory.getLogger("safra." + clazz.getSimpleName());
    }

    public static Logger get(String name) {
        return LoggerFactory.getLogger("safra." + name);
    }
}
