package autorequesttuar;

import java.awt.Component;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;

/** Optional bridge to the Toolkit account, keeping the module independently buildable. */
final class SharedAccount {

    private SharedAccount() {
    }

    static boolean isManaged() {
        return !System.getProperty("trueops.home", "").trim().isEmpty();
    }

    static Path home() {
        return Paths.get(System.getProperty("trueops.home")).toAbsolutePath().normalize();
    }

    static Optional<Credentials> load() throws IOException {
        Object saved = null;
        char[] password = null;
        try {
            Class<?> store = Class.forName("trueopstoolkit.SharedCredentials");
            Optional<?> result = (Optional<?>) store.getMethod("load", Path.class)
                    .invoke(null, home());
            if (!result.isPresent()) {
                return Optional.empty();
            }
            saved = result.get();
            String username = (String) saved.getClass().getMethod("getUsername").invoke(saved);
            password = (char[]) saved.getClass().getMethod("copyPassword").invoke(saved);
            if (username == null || username.trim().isEmpty()
                    || password == null || password.length == 0) {
                throw new IOException("Set the shared Toolkit account before running.");
            }
            Credentials copy = new Credentials(username, password);
            password = null;
            return Optional.of(copy);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IOException("Cannot read the shared Toolkit account. Open Shared account settings.", ex);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (saved instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) saved).close();
                } catch (Exception ignored) {
                    // The copied credentials are independent from the vault's cleared buffer.
                }
            }
        }
    }

    static boolean showDialog(Component parent) throws IOException {
        try {
            Class<?> dialog = Class.forName("trueopstoolkit.SharedCredentialsDialog");
            return Boolean.TRUE.equals(dialog.getMethod("showDialog", Component.class, Path.class)
                    .invoke(null, parent, home()));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Throwable cause = ex instanceof InvocationTargetException ? ex.getCause() : ex;
            throw new IOException("Cannot open the shared Toolkit account settings.", cause);
        }
    }

    static final class Credentials implements AutoCloseable {
        private final String username;
        private final char[] password;

        Credentials(String username, char[] password) {
            this.username = username;
            this.password = password;
        }

        String getUsername() {
            return username;
        }

        char[] copyPassword() {
            return Arrays.copyOf(password, password.length);
        }

        @Override
        public void close() {
            Arrays.fill(password, '\0');
        }
    }
}
