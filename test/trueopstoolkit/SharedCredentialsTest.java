package trueopstoolkit;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/** Real Windows DPAPI with synthetic account values only; no network or actual accounts. */
public final class SharedCredentialsTest {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            System.out.println("SHARED CREDENTIALS SKIPPED: Windows DPAPI required");
            return;
        }
        Path home = Files.createTempDirectory("trueops-vault-test-");
        char[] password = " synthetic, secret ไทย \uD83D\uDD10 $'\" ".toCharArray();
        String username = "synthetic-user,ทดสอบ\uD83D\uDE80";
        try {
            check(!SharedCredentials.load(home).isPresent(), "missing vault returns empty");
            check(!Files.exists(home.resolve("config")), "read creates no files");
            SharedCredentials.save(home, username, password);
            check(Arrays.equals(password, " synthetic, secret ไทย \uD83D\uDD10 $'\" ".toCharArray()), "save does not mutate caller password");
            Path vault = SharedCredentials.file(home);
            byte[] first = Files.readAllBytes(vault);
            check(!contains(first, username.getBytes(StandardCharsets.UTF_8)), "username is encrypted");
            check(!contains(first, new String(password).getBytes(StandardCharsets.UTF_8)), "password is encrypted");
            check(!contains(first, username.getBytes(StandardCharsets.UTF_16LE)), "username absent in UTF-16 ciphertext");
            check(!contains(first, new String(password).getBytes(StandardCharsets.UTF_16LE)), "password absent in UTF-16 ciphertext");
            assertAccount(home, username, password);

            SharedCredentials.Credentials credentials = SharedCredentials.load(home).get();
            char[] copied = credentials.copyPassword();
            copied[0] = 'X';
            check(Arrays.equals(password, credentials.copyPassword()), "password accessor is defensive");
            Field field = SharedCredentials.Credentials.class.getDeclaredField("password");
            field.setAccessible(true);
            char[] internal = (char[]) field.get(credentials);
            credentials.close();
            check(Arrays.equals(internal, new char[internal.length]), "close clears the owned password buffer");
            try { credentials.copyPassword(); throw new AssertionError("Closed credentials were readable"); }
            catch (IllegalStateException expected) { }
            credentials.close();
            Arrays.fill(copied, '\0');

            byte[] tampered = first.clone();
            tampered[tampered.length - 1] ^= 1;
            Files.write(vault, tampered);
            expectFailure(() -> SharedCredentials.load(home));
            expectFailure(() -> SharedCredentials.save(home, username, password));
            check(Arrays.equals(tampered, Files.readAllBytes(vault)), "tampering is rejected and never silently overwritten");
            Files.write(vault, first);

            char[] oversized = new char[8193];
            expectFailure(() -> SharedCredentials.save(home, username, oversized));
            check(Arrays.equals(first, Files.readAllBytes(vault)), "invalid save preserves existing ciphertext");

            for (String invalid : new String[]{"", " \t ", "\u00A0\u2003", "a\rb", "a\nb", "a\0b"}) {
                expectFailure(() -> SharedCredentials.save(home, invalid, password));
                expectFailure(() -> SharedCredentials.save(home, username, invalid.toCharArray()));
                check(Arrays.equals(first, Files.readAllBytes(vault)), "blank and multiline login rejection preserves existing vault");
            }
            expectFailure(() -> SharedCredentials.save(home, null, password));
            expectFailure(() -> SharedCredentials.save(home, username, null));

            Files.setAttribute(vault, "dos:readonly", true);
            try {
                expectFailure(() -> SharedCredentials.save(home, "replacement-user", "replacement-secret".toCharArray()));
                check(Arrays.equals(first, Files.readAllBytes(vault)), "failed replacement preserves previous vault");
            } finally { Files.setAttribute(vault, "dos:readonly", false); }
            assertAccount(home, username, password);
            checkOnlyVault(home);

            SharedCredentials.save(home, username, password);
            byte[] second = Files.readAllBytes(vault);
            check(!Arrays.equals(first, second), "DPAPI randomizes repeated encryption");
            assertAccount(home, username, password);
            checkOnlyVault(home);

            SharedCredentials.delete(home);
            SharedCredentials.delete(home);
            check(!SharedCredentials.load(home).isPresent(), "delete is idempotent and removes saved account");
            System.out.println("SHARED CREDENTIALS PASS");
        } finally {
            Arrays.fill(password, '\0');
            try (Stream<Path> files = Files.walk(home)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException failure) { throw new RuntimeException(failure); }
                });
            }
        }
    }

    private static void assertAccount(Path home, String username, char[] password) throws Exception {
        try (SharedCredentials.Credentials account = SharedCredentials.load(home).get()) {
            check(username.equals(account.getUsername()), "username preserves Unicode and commas");
            char[] loaded = account.copyPassword();
            try { check(Arrays.equals(password, loaded), "password preserves whitespace, punctuation and Unicode"); }
            finally { Arrays.fill(loaded, '\0'); }
        }
    }

    private static void checkOnlyVault(Path home) throws Exception {
        try (Stream<Path> files = Files.list(home.resolve("config"))) {
            check(files.count() == 1, "only encrypted vault remains after atomic save");
        }
    }

    private static boolean contains(byte[] data, byte[] token) {
        outer: for (int i = 0; i <= data.length - token.length; i++) {
            for (int j = 0; j < token.length; j++) if (data[i + j] != token[j]) continue outer;
            return true;
        }
        return false;
    }

    private interface Operation { void run() throws Exception; }

    private static void expectFailure(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected operation failure"); }
        catch (IOException expected) { }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        System.out.println("PASS: " + label);
    }
}
