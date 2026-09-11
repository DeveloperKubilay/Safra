package org.developerkubilay.safra.p2p;

import java.util.Locale;

public final class ConsoleShareCodePrinter {
    private static final String ANSI_AQUA = "\u001B[96m";
    private static final String ANSI_RESET = "\u001B[0m";

    private ConsoleShareCodePrinter() {
    }

    public static void printDedicatedShareCodeIfSupported(String shareCodeText) {
        if (!supportsAnsiConsole()) {
            return;
        }

        System.out.println("[Safra P2P] Share code: " + ANSI_AQUA + shareCodeText + ANSI_RESET);
    }

    private static boolean supportsAnsiConsole() {
        if (System.console() == null) {
            return false;
        }

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) {
            return true;
        }

        return System.getenv("WT_SESSION") != null
            || System.getenv("ANSICON") != null
            || "ON".equalsIgnoreCase(System.getenv("ConEmuANSI"))
            || System.getenv("TERM_PROGRAM") != null
            || System.getenv("TERM") != null;
    }
}