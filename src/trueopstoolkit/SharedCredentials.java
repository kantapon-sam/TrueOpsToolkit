package trueopstoolkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;

/** One shared account stored as readable UTF-8 properties, without encryption. */
public final class SharedCredentials {
    private static final String FORMAT = "TRUEOPS-CREDENTIALS-PLAIN-1";
    private static final int MAX_CHARS = 8192;
    private static final int MAX_BYTES = 131072;
    private static final String ERROR = "Cannot read the saved account. Open Shared account, remove it and save a new one.";

    private SharedCredentials() { }

    public static Path file(Path home) {
        return home.resolve("config").resolve("credentials.properties");
    }

    static Path encryptedFile(Path home) {
        return home.resolve("config").resolve("credentials.dat");
    }

    /** Retires only the old Toolkit account file, without reading or decrypting it. */
    static boolean removeEncryptedAccount(Path home) throws IOException {
        return Files.deleteIfExists(encryptedFile(home));
    }

    public static synchronized Optional<Credentials> load(Path home) throws IOException {
        byte[] stored;
        try (InputStream input = Files.newInputStream(file(home))) {
            stored = readBounded(input);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        CharBuffer decoded = null;
        Properties values = new Properties();
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(stored));
            values.load(new StringReader(decoded.toString()));
            if (values.size() != 3 || !FORMAT.equals(values.getProperty("format"))) throw new IOException(ERROR);
            String username = values.getProperty("username"), password = values.getProperty("password");
            validateField(username);
            validateField(password);
            return Optional.of(new Credentials(username, password.toCharArray()));
        } catch (IllegalArgumentException invalid) {
            throw new IOException(ERROR);
        } finally {
            Arrays.fill(stored, (byte) 0);
            if (decoded != null && decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
            values.clear();
        }
    }

    /** Commits the plaintext account atomically; a failed replacement keeps the previous file. */
    public static synchronized void save(Path home, String username, char[] password) throws IOException {
        validateField(username);
        validateField(password == null ? null : CharBuffer.wrap(password));
        Optional<Credentials> previous = load(home);
        if (previous.isPresent()) previous.get().close();

        Properties values = new Properties();
        Path temporary = null;
        try {
            values.setProperty("format", FORMAT);
            values.setProperty("username", username);
            values.setProperty("password", new String(password));
            Path target = file(home);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".credentials-", ".tmp");
            try (Writer output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(output, "TrueOps Toolkit shared account - NOT encrypted");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            removeEncryptedAccount(home);
        } finally {
            values.clear();
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    public static synchronized void delete(Path home) throws IOException {
        Files.deleteIfExists(file(home));
        removeEncryptedAccount(home);
    }

    private static void validateField(CharSequence value) throws IOException {
        if (value == null || value.length() > MAX_CHARS) throw new IOException("The shared login is invalid or too long.");
        boolean hasContent = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\r' || character == '\n' || character == '\0')
                throw new IOException("Username and password must each be a single line.");
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) hasContent = true;
        }
        if (!hasContent) throw new IOException("Username and password cannot be blank.");
    }

    public static final class Credentials implements AutoCloseable {
        private String username;
        private char[] password;

        private Credentials(String username, char[] password) {
            this.username = username;
            this.password = password;
        }

        public synchronized String getUsername() { ensureOpen(); return username; }
        public synchronized char[] copyPassword() { ensureOpen(); return password.clone(); }
        private void ensureOpen() {
            if (password == null) throw new IllegalStateException("Credentials have been closed.");
        }
        @Override public synchronized void close() {
            if (password != null) Arrays.fill(password, '\0');
            password = null;
            username = null;
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (WipingBuffer output = new WipingBuffer()) {
            byte[] buffer = new byte[4096];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES) throw new IOException(ERROR);
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } finally { Arrays.fill(buffer, (byte) 0); }
        }
    }

    private static final class WipingBuffer extends ByteArrayOutputStream {
        private WipingBuffer() { super(MAX_BYTES); }
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
