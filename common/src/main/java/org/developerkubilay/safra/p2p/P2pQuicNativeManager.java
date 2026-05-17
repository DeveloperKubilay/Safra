package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;

final class P2pQuicNativeManager {
    static final String NATIVE_HOME_PROPERTY = "safra.p2p.quicNativeHome";
    static final String NATIVE_BASE_URL_PROPERTY = "safra.p2p.quicNativeBaseUrl";

    private static final String DEFAULT_BASE_URL = "https://repo1.maven.org/maven2";
    private static final String QUIC_GROUP_PATH = "io/netty/netty-codec-native-quic";

    private P2pQuicNativeManager() {
    }

    static Path resolvePreferredNativeHome() {
        String configured = configuredNativeHome();
        if (!configured.isBlank()) {
            return Path.of(configured);
        }

        Path launcherDataRoot = inferLauncherDataRoot();
        if (launcherDataRoot != null) {
            return launcherDataRoot.resolve("bin").resolve("safra").resolve("quic");
        }

        Path gameDir = inferCommandLineDirectory("--gameDir");
        if (gameDir != null) {
            return gameDir.resolve("bin").resolve("safra").resolve("quic");
        }

        return Path.of(System.getProperty("user.home", "."), ".safra", "bin", "quic");
    }

    static Path ensureExternalNativeAvailable() throws IOException {
        String classifier = nativeClassifier();
        String nativeFileName = nativeLibraryFileName();
        if (classifier == null || nativeFileName == null) {
            throw new IOException("Safra QUIC native platform'i desteklenmiyor");
        }

        Path nativeHome = resolveNativeDirectory(classifier);
        Path extractedNative = nativeHome.resolve(nativeFileName);
        if (Files.isRegularFile(extractedNative)) {
            return extractedNative;
        }

        Files.createDirectories(nativeHome);
        Path downloadJar = ensureExternalNativeJarAvailable();

        extractNativeFromJar(downloadJar, extractedNative, nativeFileName);
        return extractedNative;
    }

    static Path ensureExternalNativeJarAvailable() throws IOException {
        String classifier = nativeClassifier();
        if (classifier == null) {
            throw new IOException("Safra QUIC native platform'i desteklenmiyor");
        }

        Path nativeHome = resolveNativeDirectory(classifier);
        Files.createDirectories(nativeHome);
        Path downloadJar = nativeHome.resolve(downloadJarFileName(classifier));
        if (!Files.isRegularFile(downloadJar)) {
            downloadNativeJar(downloadJar, classifier);
        }
        return downloadJar;
    }

    static Path resolveNativeDirectory() throws IOException {
        String classifier = nativeClassifier();
        if (classifier == null) {
            throw new IOException("Safra QUIC native platform'i desteklenmiyor");
        }
        return resolveNativeDirectory(classifier);
    }

    static String configuredNativeHome() {
        String property = System.getProperty(NATIVE_HOME_PROPERTY, "");
        if (!property.isBlank()) {
            return property.trim();
        }

        String environment = System.getenv("SAFRA_P2P_QUIC_NATIVE_HOME");
        return environment == null ? "" : environment.trim();
    }

    static String configuredBaseUrl() {
        String property = System.getProperty(NATIVE_BASE_URL_PROPERTY, "");
        if (!property.isBlank()) {
            return property.trim();
        }

        String environment = System.getenv("SAFRA_P2P_QUIC_NATIVE_BASE_URL");
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }

        return DEFAULT_BASE_URL;
    }

    static String nativeClassifier() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean aarch64 = osArch.contains("aarch64") || osArch.contains("arm64");
        if (osName.contains("win")) {
            return "windows-x86_64";
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

    private static void downloadNativeJar(Path targetJar, String classifier) throws IOException {
        String version = SafraBuildInfo.quicNettyVersion();
        if (version.isBlank() || "unknown".equalsIgnoreCase(version)) {
            throw new IOException("Safra QUIC native surumu bilinmiyor");
        }

        String artifact = "netty-codec-native-quic-" + version + "-" + classifier + ".jar";
        String baseUrl = configuredBaseUrl().replace('\\', '/').replaceAll("/+$", "");
        URI uri = URI.create(baseUrl + "/" + QUIC_GROUP_PATH + "/" + version + "/" + artifact);

        Path tempFile = Files.createTempFile("safra-quic-native-", ".jar");
        try {
            URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(P2pConstants.QUIC_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(P2pConstants.QUIC_CONNECT_TIMEOUT_MS * 3);
            connection.setRequestProperty("User-Agent", "Safra/" + SafraBuildInfo.modVersion());
            try (InputStream inputStream = connection.getInputStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tempFile, targetJar, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static void extractNativeFromJar(Path downloadJar, Path extractedNative, String nativeFileName) throws IOException {
        Path tempFile = Files.createTempFile("safra-quic-native-", "-" + nativeFileName);
        try (JarFile jarFile = new JarFile(downloadJar.toFile())) {
            var entry = jarFile.getJarEntry("META-INF/native/" + nativeFileName);
            if (entry == null) {
                throw new IOException("Safra QUIC native jar icinde " + nativeFileName + " bulunamadi");
            }
            try (InputStream inputStream = jarFile.getInputStream(entry)) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tempFile, extractedNative, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static String downloadJarFileName(String classifier) {
        return "netty-codec-native-quic-" + SafraBuildInfo.quicNettyVersion() + "-" + classifier + ".jar";
    }

    private static Path resolveNativeDirectory(String classifier) {
        return resolvePreferredNativeHome()
            .resolve(SafraBuildInfo.quicNettyVersion())
            .resolve(classifier);
    }

    private static Path inferLauncherDataRoot() {
        Path assetsDir = inferCommandLineDirectory("--assetsDir");
        if (assetsDir == null) {
            return null;
        }

        Path cacheDir = assetsDir.getParent();
        return cacheDir == null ? null : cacheDir.getParent();
    }

    private static Path inferCommandLineDirectory(String argumentName) {
        List<String> tokens = commandTokens();
        for (int index = 0; index < tokens.size() - 1; index++) {
            if (argumentName.equals(tokens.get(index))) {
                return Path.of(tokens.get(index + 1));
            }
        }
        return null;
    }

    private static List<String> commandTokens() {
        String rawCommand = System.getProperty("sun.java.command", "");
        if (rawCommand.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < rawCommand.length(); index++) {
            char character = rawCommand.charAt(index);
            if (character == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(character) && !quoted) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(character);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
