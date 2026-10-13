package trueopstoolkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One account for all modules, protected by Windows DPAPI for the current Windows user.
 * The key is managed by Windows, never derived from public machine/user names or stored
 * alongside this file. Other programs running as this Windows user can also decrypt it.
 */
public final class SharedCredentials {
    private static final byte[] HEADER = "TRUEOPS-CREDENTIALS-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int PAYLOAD_MAGIC = 0x544f4331;
    private static final int MAX_CHARS = 8192;
    private static final int MAX_BYTES = 131072;
    private static final int PROCESS_TIMEOUT_SECONDS = 20;
    private static final String ERROR = "Cannot access the shared login. Use the Windows account that saved it and check that Windows PowerShell is available.";

    private SharedCredentials() { }

    public static Path file(Path home) {
        return home.resolve("config").resolve("credentials.dat");
    }

    public static synchronized Optional<Credentials> load(Path home) throws IOException {
        byte[] stored;
        try (InputStream input = Files.newInputStream(file(home))) {
            stored = readBounded(input);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        byte[] protectedBytes = null;
        byte[] plain = null;
        try {
            if (stored.length <= HEADER.length) throw new IOException(ERROR);
            for (int i = 0; i < HEADER.length; i++) {
                if (stored[i] != HEADER[i]) throw new IOException(ERROR);
            }
            protectedBytes = Arrays.copyOfRange(stored, HEADER.length, stored.length);
            plain = dpapi(protectedBytes, false);
            return Optional.of(decode(plain));
        } finally {
            clear(stored);
            clear(protectedBytes);
            clear(plain);
        }
    }

    /** Saves only ciphertext, then atomically replaces the previous file on the same volume. */
    public static synchronized void save(Path home, String username, char[] password) throws IOException {
        if (username == null || password == null || username.length() > MAX_CHARS || password.length > MAX_CHARS)
            throw new IOException("The shared login is invalid or too long.");
        validateField(CharBuffer.wrap(username));
        validateField(CharBuffer.wrap(password));

        // An unreadable existing vault must not silently be overwritten by a new account.
        Optional<Credentials> previous = load(home);
        if (previous.isPresent()) previous.get().close();

        byte[] plain = null;
        byte[] protectedBytes = null;
        Path temporary = null;
        try {
            plain = encode(username, password);
            protectedBytes = dpapi(plain, true);
            Path target = file(home);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".credentials-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                output.write(HEADER);
                output.write(protectedBytes);
            }
            // No non-atomic fallback: a failure must leave the old vault intact.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            clear(plain);
            clear(protectedBytes);
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    public static synchronized void delete(Path home) throws IOException {
        Files.deleteIfExists(file(home));
    }

    private static void validateField(CharSequence value) throws IOException {
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

        public synchronized String getUsername() {
            ensureOpen();
            return username;
        }

        public synchronized char[] copyPassword() {
            ensureOpen();
            return password.clone();
        }

        private void ensureOpen() {
            if (password == null) throw new IllegalStateException("Credentials have been closed.");
        }

        @Override public synchronized void close() {
            if (password != null) Arrays.fill(password, '\0');
            password = null;
            username = null;
        }
    }

    private static byte[] encode(String username, char[] password) throws IOException {
        byte[] user = null;
        byte[] secret = null;
        try {
            user = utf8(CharBuffer.wrap(username));
            secret = utf8(CharBuffer.wrap(password));
            return ByteBuffer.allocate(12 + user.length + secret.length)
                    .putInt(PAYLOAD_MAGIC).putInt(user.length).putInt(secret.length).put(user).put(secret).array();
        } finally {
            clear(user);
            clear(secret);
        }
    }

    private static byte[] utf8(CharBuffer value) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(value);
        try {
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } finally {
            if (encoded.hasArray()) clear(encoded.array());
        }
    }

    private static Credentials decode(byte[] plain) throws IOException {
        if (plain.length < 12) throw new IOException(ERROR);
        ByteBuffer buffer = ByteBuffer.wrap(plain);
        if (buffer.getInt() != PAYLOAD_MAGIC) throw new IOException(ERROR);
        int userLength = buffer.getInt(), passwordLength = buffer.getInt();
        if (userLength < 0 || passwordLength < 0 || userLength > MAX_BYTES / 2 || passwordLength > MAX_BYTES / 2
                || userLength + passwordLength != buffer.remaining()) throw new IOException(ERROR);
        CharBuffer user = null, secret = null;
        try {
            user = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain, 12, userLength));
            secret = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain, 12 + userLength, passwordLength));
            if (user.remaining() > MAX_CHARS || secret.remaining() > MAX_CHARS) throw new IOException(ERROR);
            char[] password = new char[secret.remaining()];
            secret.get(password);
            return new Credentials(user.toString(), password);
        } catch (CharacterCodingException invalid) {
            throw new IOException(ERROR);
        } finally {
            if (user != null && user.hasArray()) Arrays.fill(user.array(), '\0');
            if (secret != null && secret.hasArray()) Arrays.fill(secret.array(), '\0');
        }
    }

    private static byte[] dpapi(final byte[] input, boolean protect) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            throw new IOException("The shared login requires Windows DPAPI.");
        String windows = System.getenv("SystemRoot");
        if (windows == null || windows.isEmpty()) throw new IOException(ERROR);
        Path powershell = Paths.get(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(powershell)) throw new IOException(ERROR);

        // Fixed code only in process arguments. All input/output travels over private binary
        // pipes, without secrets in command lines, environment variables, logs or temp files.
        String script = "$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue';"
                + "$bytes=$null;$result=$null;$buffer=New-Object byte[] " + (MAX_BYTES + 1) + ";$status=1;"
                + "try {Add-Type -AssemblyName System.Security;"
                + "$stream=[Console]::OpenStandardInput();$length=0;"
                + "while($length -lt $buffer.Length){$count=$stream.Read($buffer,$length,$buffer.Length-$length);"
                + "if($count -eq 0){break};$length+=$count};"
                + "if($length -gt " + MAX_BYTES + "){throw 'Invalid data'};"
                + "$bytes=New-Object byte[] $length;[Array]::Copy($buffer,$bytes,$length);"
                + "$entropy=[Text.Encoding]::UTF8.GetBytes('TrueOpsToolkit.SharedCredentials.v1');"
                + "$result=[Security.Cryptography.ProtectedData]::" + (protect ? "Protect" : "Unprotect")
                + "($bytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
                + "$output=[Console]::OpenStandardOutput();$output.Write($result,0,$result.Length);$output.Flush();$status=0"
                + "} catch {} finally {"
                + "if($null -ne $bytes){[Array]::Clear($bytes,0,$bytes.Length)};"
                + "if($null -ne $result){[Array]::Clear($result,0,$result.Length)};"
                + "[Array]::Clear($buffer,0,$buffer.Length)};exit $status";

        final Process process;
        try {
            process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-Command", script).start();
        } catch (IOException unavailable) {
            throw new IOException(ERROR);
        }
        ExecutorService io = Executors.newFixedThreadPool(3, new ThreadFactory() {
            @Override public Thread newThread(Runnable action) {
                Thread thread = new Thread(action, "trueops-credentials-pipe");
                thread.setDaemon(true);
                return thread;
            }
        });
        Future<Void> writer = io.submit(new Callable<Void>() {
            @Override public Void call() throws IOException {
                try (OutputStream pipe = process.getOutputStream()) { pipe.write(input); }
                return null;
            }
        });
        final AtomicBoolean discardOutput = new AtomicBoolean(false);
        final AtomicReference<byte[]> pipeOutput = new AtomicReference<byte[]>();
        Future<byte[]> reader = io.submit(new Callable<byte[]>() {
            @Override public byte[] call() throws IOException {
                try (InputStream pipe = process.getInputStream()) {
                    byte[] value = readBounded(pipe);
                    pipeOutput.set(value);
                    if (discardOutput.get()) clear(value);
                    return value;
                }
            }
        });
        Future<Void> errors = io.submit(new Callable<Void>() {
            @Override public Void call() throws IOException {
                try (InputStream pipe = process.getErrorStream()) {
                    byte[] discard = new byte[1024];
                    try { while (pipe.read(discard) != -1) { /* never log process output */ } }
                    finally { clear(discard); }
                }
                return null;
            }
        });
        byte[] result = null;
        boolean accepted = false;
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IOException(ERROR);
            writer.get(2, TimeUnit.SECONDS);
            result = reader.get(2, TimeUnit.SECONDS);
            errors.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || result.length == 0) throw new IOException(ERROR);
            accepted = true;
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(ERROR);
        } catch (Exception failed) {
            throw new IOException(ERROR);
        } finally {
            if (!accepted) {
                discardOutput.set(true);
                clear(result);
                clear(pipeOutput.get());
            }
            process.destroyForcibly();
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            writer.cancel(true);
            reader.cancel(true);
            errors.cancel(true);
            io.shutdownNow();
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
            } finally { clear(buffer); }
        }
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }

    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static final class WipingBuffer extends ByteArrayOutputStream {
        private WipingBuffer() {
            // Avoid growth leaving extra plaintext copies in abandoned backing arrays.
            super(MAX_BYTES);
        }

        @Override public void close() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
