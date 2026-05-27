package org.developerkubilay.safra.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Java8Compat {
    private Java8Compat() {
    }

    public static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }

        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    public static int unsignedByteToInt(byte value) {
        return value & 0xFF;
    }

    public static int unsignedShortToInt(short value) {
        return value & 0xFFFF;
    }

    public static int parseUnsignedInt(String value, int radix) {
        long parsed = Long.parseLong(value, radix);
        if ((parsed & 0xFFFFFFFF00000000L) != 0L) {
            throw new NumberFormatException("Value out of range for unsigned int: " + value);
        }
        return (int) parsed;
    }

    public static String toUnsignedString(int value, int radix) {
        return Long.toString(value & 0xFFFFFFFFL, radix);
    }

    public static <T> List<T> immutableListCopy(Collection<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    public static <K, V> Map<K, V> immutableMapCopy(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(values));
    }
}
