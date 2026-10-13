package com.java.myapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Offline parser coverage; run with one PROMS module on the classpath at a time. */
public final class PromsSettingsTest {
    private static final String PASSWORD = "  รหัส,synthetic,only  ";
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("approve".equals(args[0])
                || "approve-ref".equals(args[0]) || "delete".equals(args[0]))) {
            throw new IllegalArgumentException("Expected module id: approve, approve-ref, or delete.");
        }
        Path directory = Files.createTempDirectory("trueops-proms-settings-test-");
        try {
            Path login = directory.resolve("Login.txt");
            testLogin(login);
            testSearch(login);
            testReferences(directory.resolve("Ref_No.csv"));
            testDriverPaths(directory);
            testSharedAccount(directory);
            System.out.println("PASS: " + args[0] + " offline PROMS settings (" + checks + " checks).");
        } finally {
            Path[] cleanup;
            try (Stream<Path> paths = Files.walk(directory)) {
                cleanup = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            }
            for (Path path : cleanup) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void testLogin(Path login) throws Exception {
        // Password and Description precede Search and User, including lines after Password.
        write(login, "\uFEFFPassword," + PASSWORD + "\r\n"
                + "Description,รายละเอียด,เพิ่มเติม\r\n\r\n"
                + "Search,รายการ,ทดสอบ\r\nUser,ผู้ใช้,ทดสอบ\r\nIgnored,setting\r\n"
                + "# Legacy comment without a comma\r\n");
        PromsSettings settings = PromsSettings.readLogin(login.toString(), true);
        equal("ผู้ใช้,ทดสอบ", settings.get("User"), "UTF-8 username and comma");
        equal(PASSWORD, settings.get("Password"), "Password whitespace and commas");
        equal("รายการ,ทดสอบ", settings.get("Search"), "Search after password");
        equal("รายละเอียด,เพิ่มเติม", settings.get("Description"), "Description before password wait");
        equal("", settings.get("Ignored"), "Unsupported keys are ignored");
        equal("", settings.get("missing"), "Missing optional key");

        write(login, "User,synthetic-user\nPassword," + PASSWORD + "\nDescription,\n");
        settings = PromsSettings.readLogin(login.toString(), false);
        equal("", settings.get("Description"), "Empty description is safe");
        equal("", settings.get("Search"), "Ref-based modules need no Search");
        failLogin(login, "User,\nPassword," + PASSWORD + "\n", false, "User and Password");
        failLogin(login, "User,   \nPassword," + PASSWORD + "\n", false, "User and Password");
        failLogin(login, "User,synthetic-user\nPassword,\n", false, "User and Password");
        failLogin(login, "User,synthetic-user\n", false, "User and Password");
        failLogin(login, "Password," + PASSWORD + "\nUserBackup,synthetic-user\n", false, "User and Password");
        failLogin(login, "\uFEFF\n\n", false, "User and Password");
        // A known key without a separator is an empty setting, including after a valid value.
        failLogin(login, "User,synthetic-user\nPassword," + PASSWORD + "\nPassword\n", false, "User and Password");
        failLogin(login, "User\nPassword," + PASSWORD + "\n", false, "User and Password");
    }

    private static void testSearch(Path login) throws Exception {
        String credentials = "User,synthetic-user\nPassword," + PASSWORD + "\n";
        for (String search : new String[] {"", "   ", "&&", "first&&", "&&second",
                "first&&   ", "first&&second&&third"}) {
            failLogin(login, credentials + "Search," + search + "\n", true, "Search");
        }
        failLogin(login, credentials, true, "Search");
        write(login, credentials + "Search,first&&second\n");
        equal("first&&second", PromsSettings.readLogin(login.toString(), true).get("Search"),
                "Two non-empty search terms");
        write(login, credentials + "Search,  ค้นหา  \n");
        equal("ค้นหา", PromsSettings.readLogin(login.toString(), true).get("Search"), "Search edge whitespace");
    }

    private static void testReferences(Path refs) throws Exception {
        write(refs, "\uFEFF REF-001 , Services ,รายละเอียด,เพิ่มเติม\r\n\r\nREF-002,Type,\r\n");
        List<String[]> rows = PromsSettings.readReferences(refs.toString(), 3);
        equal(2, rows.size(), "Blank approval rows are skipped");
        equal("REF-001", rows.get(0)[0], "Reference whitespace");
        equal("Services", rows.get(0)[1], "Type whitespace");
        equal("รายละเอียด,เพิ่มเติม", rows.get(0)[2], "Approval description with comma");
        equal("", rows.get(1)[2], "Runtime accepts empty approval description");

        write(refs, "\uFEFFREF-001,เหตุผล,เพิ่มเติม\n  \nREF-002,เหตุผลที่สอง\n");
        rows = PromsSettings.readReferences(refs.toString(), 2);
        equal(2, rows.size(), "Blank deletion rows are skipped");
        equal("เหตุผล,เพิ่มเติม", rows.get(0)[1], "Deletion reason with comma");

        failReferences(refs, "REF-001,Type\n", 3, "row 1");
        failReferences(refs, ",Type,description\n", 3, "row 1");
        failReferences(refs, "REF-001,,description\n", 3, "row 1");
        failReferences(refs, "REF-001,  ,description\n", 3, "row 1");
        failReferences(refs, "REF-001\n", 2, "row 1");
        failReferences(refs, ",reason\n", 2, "row 1");
        failReferences(refs, "REF-001,\n", 2, "row 1");
        failReferences(refs, "REF-001,   \n", 2, "row 1");
        for (int columns : new int[] {2, 3}) {
            failReferences(refs, "\uFEFF\n   \n", columns, "at least one reference");
            String good = columns == 3 ? "REF-001,Type,description" : "REF-001,reason";
            // Later invalid rows reject the whole input before the caller can start work.
            failReferences(refs, good + "\n\nmalformed\n", columns, "row 3");
        }
    }

    private static void testDriverPaths(Path directory) throws Exception {
        String originalDriver = System.getProperty("webdriver.chrome.driver");
        String originalHome = System.getProperty("trueops.home");
        try {
            System.clearProperty("webdriver.chrome.driver");
            System.setProperty("trueops.home", directory.toString());
            PathFolder folder = new PathFolder();
            equal(directory.resolve("drivers").resolve("chromedriver.exe").toAbsolutePath().toString(),
                    folder.getChromedriver(), "Shared Toolkit driver");
            String custom = directory.resolve("explicit-driver.exe").toString();
            System.setProperty("webdriver.chrome.driver", custom);
            equal(custom, folder.getChromedriver(), "Explicit driver overrides Toolkit home");
        } finally {
            restoreProperty("webdriver.chrome.driver", originalDriver);
            restoreProperty("trueops.home", originalHome);
        }
    }

    private static void testSharedAccount(Path directory) throws Exception {
        String originalHome = System.getProperty("trueops.home");
        Path sharedHome = directory.resolve("toolkit");
        Path login = directory.resolve("Login.txt");
        char[] password = PASSWORD.toCharArray();
        Class<?> vault = Class.forName("trueopstoolkit.SharedCredentials");
        try {
            System.setProperty("trueops.home", sharedHome.toString());
            String legacy = "User,synthetic-stale-user\nPassword,synthetic-stale-password\n"
                    + "Search,first&&second\nDescription,synthetic-description\n";
            failLogin(login, legacy, true, "shared Toolkit account");
            vault.getMethod("save", Path.class, String.class, char[].class)
                    .invoke(null, sharedHome, "synthetic-shared-user", password);
            write(login, legacy);
            PromsSettings settings = PromsSettings.readLogin(login.toString(), true);
            equal("synthetic-shared-user", settings.get("User"), "Shared account overrides legacy User");
            equal(PASSWORD, settings.get("Password"), "Shared account overrides legacy Password");
            equal("first&&second", settings.get("Search"), "Search stays per module");
            equal("synthetic-description", settings.get("Description"), "Description stays per module");
            write(login, "Search,first&&second\nDescription,synthetic-description\n");
            equal("synthetic-shared-user", PromsSettings.readLogin(login.toString(), true).get("User"),
                    "Non-secret settings need no local User or Password");
            vault.getMethod("save", Path.class, String.class, char[].class)
                    .invoke(null, sharedHome, "synthetic-replacement-user", password);
            equal("synthetic-replacement-user", PromsSettings.readLogin(login.toString(), true).get("User"),
                    "Fresh execution reloads shared account");
            vault.getMethod("delete", Path.class).invoke(null, sharedHome);
            failLogin(login, legacy, true, "shared Toolkit account");
            Path vaultFile = (Path) vault.getMethod("file", Path.class).invoke(null, sharedHome);
            Files.write(vaultFile, "synthetic-corrupt-vault".getBytes(StandardCharsets.US_ASCII));
            failLogin(login, legacy, true, "shared Toolkit account");
        } finally {
            Arrays.fill(password, '\0');
            restoreProperty("trueops.home", originalHome);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static void failLogin(Path path, String content, boolean requireSearch, String expected) throws Exception {
        write(path, content);
        try {
            PromsSettings.readLogin(path.toString(), requireSearch);
            throw new AssertionError("Expected invalid login settings to be rejected.");
        } catch (IOException error) {
            failure(error, expected);
        }
    }

    private static void failReferences(Path path, String content, int columns, String expected) throws Exception {
        write(path, content);
        try {
            PromsSettings.readReferences(path.toString(), columns);
            throw new AssertionError("Expected invalid reference rows to be rejected.");
        } catch (IOException error) {
            failure(error, expected);
        }
    }

    private static void failure(IOException error, String expected) {
        if (error.getMessage() == null || !error.getMessage().contains(expected)) {
            throw new AssertionError("Validation error did not identify the expected field or row.");
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(PASSWORD)) {
                throw new AssertionError("Validation error exposed a password.");
            }
        }
        checks++;
    }

    private static void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " did not match.");
        }
        checks++;
    }
}
