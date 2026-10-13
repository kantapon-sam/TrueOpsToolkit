package autorequesttuar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/** Offline persistence and validation checks using synthetic data only. */
public final class SettingsStoreTest {

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("tuar-settings-test-");
        try {
            SettingsStore settings = new SettingsStore(temporary.resolve("User/settings.properties"));
            check(settings.load().isEmpty(), "Missing settings should load defaults.");
            Properties first = new Properties();
            first.setProperty("workbook", temporary.resolve("งาน TUAR.xlsx").toString());
            first.setProperty("attachments", temporary.resolve("เอกสารแนบ").toString());
            first.setProperty("rememberLogin", "true");
            first.setProperty("headless", "true");
            first.setProperty("futureSetting", "preserved");
            settings.save(first);
            Properties change = new Properties();
            change.setProperty("rememberLogin", "false");
            settings.save(change);
            Properties loaded = settings.load();
            check("false".equals(loaded.getProperty("rememberLogin")), "Remember setting was not updated.");
            check(first.getProperty("workbook").equals(loaded.getProperty("workbook")), "Unicode workbook path changed.");
            check("preserved".equals(loaded.getProperty("futureSetting")), "Unrelated settings were lost.");
            check(!loaded.containsKey("password"), "Preferences must not contain a password.");

            Path workbook = temporary.resolve("input.xlsx");
            Files.write(workbook, new byte[]{0});
            check(workbook.equals(SettingsStore.workbookPath(workbook.toString())), "Workbook was not normalized.");
            check(SettingsStore.workbookPath("relative.xlsx").isAbsolute(), "Relative workbook must resolve beside app.");
            expectInvalid(() -> SettingsStore.workbookPath(""));
            expectInvalid(() -> SettingsStore.workbookPath("requests.csv"));
            expectInvalid(() -> SettingsStore.workbookPath(temporary.getRoot().toString()));
            expectInvalid(() -> SettingsStore.attachmentsPath(workbook.toString()));
            Path folder = temporary.resolve("folder.xlsx");
            Files.createDirectories(folder);
            expectInvalid(() -> SettingsStore.workbookPath(folder.toString()));

            Path loginFile = temporary.resolve("User/login.properties");
            CredentialStore credentials = new CredentialStore(loginFile);
            char[] password = "synthetic-test-password".toCharArray();
            try {
                credentials.save("http://010.050.064.186/tuar", " synthetic-user ", password, "CLLS");
                try (CredentialStore.SavedCredentials saved = credentials.load().get()) {
                    check("10.50.64.186".equals(saved.getServerIp()), "Saved server IP was not normalized.");
                    check("synthetic-user".equals(saved.getUsername()), "Saved username changed.");
                    check("CLLS".equals(saved.getLoginType()), "Saved login type changed.");
                    char[] recovered = saved.copyPassword();
                    try {
                        check(Arrays.equals(password, recovered), "Encrypted password did not round-trip.");
                    } finally {
                        Arrays.fill(recovered, '\0');
                    }
                }
                String raw = new String(Files.readAllBytes(loginFile), StandardCharsets.ISO_8859_1);
                check(!raw.contains("synthetic-test-password"), "Password was written as plaintext.");
                credentials.delete();
                check(!credentials.load().isPresent(), "Forget did not remove saved credentials.");
                check(settings.load().containsKey("workbook"), "Forget removed unrelated settings.");
            } finally {
                Arrays.fill(password, '\0');
            }
            testSharedAccount(temporary, settings);
            System.out.println("PASS: TUAR settings, shared encrypted account, paths, and isolated legacy credentials.");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(temporary)) {
                Path[] paths = files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void testSharedAccount(Path temporary, SettingsStore settings) throws Exception {
        String previousHome = System.getProperty("trueops.home");
        Path sharedHome = temporary.resolve("toolkit");
        Class<?> vault = Class.forName("trueopstoolkit.SharedCredentials");
        char[] password = "synthetic-shared-password".toCharArray();
        try {
            System.setProperty("trueops.home", sharedHome.toString());
            check(SharedAccount.isManaged(), "Toolkit mode was not detected.");
            check(!SharedAccount.load().isPresent(), "Missing shared account used a fallback.");
            Path forbiddenFile = temporary.resolve("managed-login.properties");
            try {
                new CredentialStore(forbiddenFile).save("10.50.64.186", "synthetic-user", password, "HR");
                throw new AssertionError("Managed TUAR saved per-module credentials.");
            } catch (java.io.IOException expected) {
                check(!Files.exists(forbiddenFile), "A per-module credential file was created.");
            }
            vault.getMethod("save", Path.class, String.class, char[].class)
                    .invoke(null, sharedHome, "synthetic-shared-user", password);
            try (SharedAccount.Credentials account = SharedAccount.load().get()) {
                check("synthetic-shared-user".equals(account.getUsername()), "TUAR did not load the shared username.");
                char[] copy = account.copyPassword();
                try {
                    check(Arrays.equals(password, copy), "TUAR did not load the shared password.");
                } finally {
                    Arrays.fill(copy, '\0');
                }
            }
            Properties legacy = settings.load();
            legacy.setProperty("username", "synthetic-stale-user");
            legacy.setProperty("password", "synthetic-stale-password");
            legacy.setProperty("rememberLogin", "true");
            settings.save(legacy);
            Properties clean = settings.load();
            check(!clean.containsKey("username"), "Managed TUAR settings retained a username.");
            check(!clean.containsKey("password"), "Managed TUAR settings retained a password.");
            check(!clean.containsKey("rememberLogin"), "Managed TUAR settings retained per-module remember state.");
            check("preserved".equals(clean.getProperty("futureSetting")), "Shared-account change lost unrelated settings.");
            vault.getMethod("delete", Path.class).invoke(null, sharedHome);
            check(!SharedAccount.load().isPresent(), "Deleted account reused stale credentials.");
            Path vaultFile = (Path) vault.getMethod("file", Path.class).invoke(null, sharedHome);
            Files.write(vaultFile, "synthetic-corrupt-vault".getBytes(StandardCharsets.US_ASCII));
            try {
                SharedAccount.load();
                throw new AssertionError("Corrupt shared account reused a fallback.");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("shared Toolkit account"), "Shared account error was not actionable.");
            }
        } finally {
            Arrays.fill(password, '\0');
            if (previousHome == null) {
                System.clearProperty("trueops.home");
            } else {
                System.setProperty("trueops.home", previousHome);
            }
        }
    }

    private static void expectInvalid(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected invalid setting to be rejected.");
        } catch (IllegalArgumentException expected) {
            // Correct validation outcome.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
