package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class P2pQuicNativeManager {
    private static volatile Path extractedNativeLibrary;

    private P2pQuicNativeManager() {
    }

    static Path ensureBundledNativeAvailable() throws IOException {
        Path cached = extractedNativeLibrary;
        if (cached != null && Files.isRegularFile(cached)) {
            return cached;
        }

        String nativeFileName = nativeLibraryFileName();
        if (nativeFileName == null) {
            throw new IOException("Safra QUIC native platform is not supported");
        }

        synchronized (P2pQuicNativeManager.class) {
            cached = extractedNativeLibrary;
            if (cached != null && Files.isRegularFile(cached)) {
                return cached;
            }

            extractedNativeLibrary = extractBundledNative(nativeFileName);
            return extractedNativeLibrary;
        }
    }

    static String nativeClassifier() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean aarch64 = osArch.contains("aarch64") || osArch.contains("arm64");
        if (osName.contains("win")) {
            return aarch64 ? null : "windows-x86_64";
        }
        if (osName.contains("mac")) {
            return aarch64 ? "osx-aarch_64" : "osx-x86_64";
        }
        if (osName.contains("linux")) {
            return aarch64 ? "linux-aarch_64" : "linux-x86_64";
        }
        return null;
    }

    static String nativeLibraryFileName() {
        String classifier = nativeClassifier();
        if (classifier == null) {
            return null;
        }
        return switch (classifier) {
            case "windows-x86_64" -> "netty_quiche42_windows_x86_64.dll";
            case "osx-x86_64" -> "libnetty_quiche42_osx_x86_64.jnilib";
            case "osx-aarch_64" -> "libnetty_quiche42_osx_aarch_64.jnilib";
            case "linux-x86_64" -> "libnetty_quiche42_linux_x86_64.so";
            case "linux-aarch_64" -> "libnetty_quiche42_linux_aarch_64.so";
            default -> null;
        };
    }

    private static Path extractBundledNative(String nativeFileName) throws IOException {
        try (InputStream inputStream = P2pQuicNativeManager.class.getResourceAsStream("/META-INF/native/" + nativeFileName)) {
            if (inputStream == null) {
                throw new IOException("Safra QUIC bundled native was not found: " + nativeFileName);
            }

            Path tempFile = Files.createTempFile("safra-quic-native-", "-" + nativeFileName);
            tempFile.toFile().deleteOnExit();
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        }
    }
}
