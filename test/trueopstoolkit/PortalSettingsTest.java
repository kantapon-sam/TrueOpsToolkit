package trueopstoolkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/** Offline coverage for the shared fixed External/Internal portal selection. */
public final class PortalSettingsTest {
    private static int checks;
    private static final List<String> KEYS = Arrays.asList("trueops.portal.mode", "trueops.portal.login",
            "trueops.portal.pending", "trueops.portal.workorder");

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("trueops-portal-test-");
        String[] previous = new String[KEYS.size()];
        for (int index = 0; index < previous.length; index++) previous[index] = System.getProperty(KEYS.get(index));
        try {
            defaultsAndExactUrls(directory.resolve("toolkit"));
            roundTripAndApply(directory.resolve("saved"));
            invalidModes(directory.resolve("invalid"));
            fileFailures(directory.resolve("file-errors"));
            System.out.println("PASS: shared portal settings (" + checks + " checks).");
        } finally {
            for (int index = 0; index < previous.length; index++) {
                if (previous[index] == null) System.clearProperty(KEYS.get(index));
                else System.setProperty(KEYS.get(index), previous[index]);
            }
            Path[] paths;
            try (Stream<Path> walk = Files.walk(directory)) {
                paths = walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            }
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static void defaultsAndExactUrls(Path home) throws Exception {
        equal(PortalSettings.Mode.EXTERNAL, PortalSettings.load(home), "First-run default is External");
        equal(false, Files.exists(home), "Read-only default lookup creates no files");
        equal("https://proms.truecorp.co.th/proms/login/", PortalSettings.Mode.EXTERNAL.getLoginUrl(), "External login path");
        equal("https://proms.truecorp.co.th/proms/pendingapprove", PortalSettings.Mode.EXTERNAL.getPendingUrl(), "External pending path");
        equal("https://proms.truecorp.co.th/proms/workorder", PortalSettings.Mode.EXTERNAL.getWorkOrderUrl(), "External work-order path");
        equal("http://promsweb.true.th/proms/login", PortalSettings.Mode.INTERNAL.getLoginUrl(), "Internal login path");
        equal("http://promsweb.true.th/proms/pendingapprove", PortalSettings.Mode.INTERNAL.getPendingUrl(), "Internal pending path");
        equal("http://promsweb.true.th/proms/workorder", PortalSettings.Mode.INTERNAL.getWorkOrderUrl(), "Internal work-order path");
        equal("External", PortalSettings.Mode.EXTERNAL.getLabel(), "External UI label");
        equal("Internal", PortalSettings.Mode.INTERNAL.getLabel(), "Internal UI label");
        PortalSettings.apply(home);
        assertApplied(PortalSettings.Mode.EXTERNAL);
    }

    private static void roundTripAndApply(Path home) throws Exception {
        Path config = home.resolve("config");
        Path file = config.resolve("portal.properties");
        PortalSettings.save(home, PortalSettings.Mode.INTERNAL);
        equal(PortalSettings.Mode.INTERNAL, PortalSettings.load(home), "Internal mode round trip");
        equal("portal.mode=INTERNAL\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8), "File stores only selected mode");
        Properties settings = new Properties();
        try (java.io.InputStream input = Files.newInputStream(file)) { settings.load(input); }
        equal(1, settings.size(), "No username/password or URL entries stored");
        equal("INTERNAL", settings.getProperty("portal.mode"), "Saved property value");
        PortalSettings.apply(home);
        assertApplied(PortalSettings.Mode.INTERNAL);
        PortalSettings.save(home, PortalSettings.Mode.EXTERNAL);
        equal(PortalSettings.Mode.EXTERNAL, PortalSettings.load(home), "External replaces Internal atomically");
        PortalSettings.apply(home);
        assertApplied(PortalSettings.Mode.EXTERNAL);
        equal("portal.mode=EXTERNAL\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8), "Replacement preserves closed settings schema");
        try (Stream<Path> files = Files.list(config)) {
            equal(1L, files.count(), "No temporary settings files left after save");
        }
        byte[] saved = Files.readAllBytes(file);
        fail(() -> PortalSettings.save(home, null), "Null mode rejected");
        equal(true, Arrays.equals(saved, Files.readAllBytes(file)), "Rejected save preserves prior mode");
    }

    private static void invalidModes(Path home) throws Exception {
        Path file = home.resolve("config/portal.properties");
        Files.createDirectories(file.getParent());
        for (String text : new String[] {"", "portal.mode=\n", "portal.mode=external\n", "portal.mode=UNKNOWN\n",
                "portal.mode=https://synthetic-user:synthetic-secret@invalid.example/\n",
                "portal.login=https://invalid.example/\n", "portal.mode=EXTERNAL\nportal.login=https://invalid.example/\n",
                "portal.mode=EXTERNAL\nPassword,synthetic-secret\n", "portal.mode=\\u12ZZ\n"}) {
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
            fail(() -> PortalSettings.load(home), "Invalid or tampered portal config rejected");
            equal(PortalSettings.Mode.EXTERNAL.getLoginUrl(), System.getProperty("trueops.portal.login"),
                    "Invalid config does not replace active fixed URLs");
            fail(() -> PortalSettings.apply(home), "Invalid portal config cannot be applied");
        }
    }

    private static void fileFailures(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path homeFile = directory.resolve("home-file");
        Files.write(homeFile, new byte[] {1});
        fail(() -> PortalSettings.load(homeFile), "File cannot be Toolkit home");
        fail(() -> PortalSettings.save(homeFile, PortalSettings.Mode.EXTERNAL), "Save cannot use file as Toolkit home");
        Path configFileHome = directory.resolve("config-file");
        Files.createDirectories(configFileHome);
        Files.write(configFileHome.resolve("config"), new byte[] {1});
        fail(() -> PortalSettings.load(configFileHome), "Config path cannot be a file");
        fail(() -> PortalSettings.save(configFileHome, PortalSettings.Mode.EXTERNAL), "Save cannot overwrite config directory with file");
        Path portalDirectoryHome = directory.resolve("portal-directory");
        Files.createDirectories(portalDirectoryHome.resolve("config/portal.properties"));
        fail(() -> PortalSettings.load(portalDirectoryHome), "Portal settings path cannot be a directory");
        fail(() -> PortalSettings.save(portalDirectoryHome, PortalSettings.Mode.EXTERNAL), "Save cannot replace portal settings directory");
        try (Stream<Path> files = Files.list(portalDirectoryHome.resolve("config"))) {
            equal(1L, files.count(), "Failed atomic replacement leaves no temporary files");
        }
    }

    private static void assertApplied(PortalSettings.Mode mode) {
        equal(mode.name(), System.getProperty("trueops.portal.mode"), "Shared current mode property");
        equal(mode.getLoginUrl(), System.getProperty("trueops.portal.login"), "Shared current login URL");
        equal(mode.getPendingUrl(), System.getProperty("trueops.portal.pending"), "Shared current pending URL");
        equal(mode.getWorkOrderUrl(), System.getProperty("trueops.portal.workorder"), "Shared current work-order URL");
    }

    private interface FileAction { void run() throws IOException; }

    private static void fail(FileAction action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IOException expected) {
            for (Throwable cause = expected; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().contains("synthetic-secret")) {
                    throw new AssertionError("Portal validation exposed a supplied secret.");
                }
            }
            checks++;
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        checks++;
    }
}
