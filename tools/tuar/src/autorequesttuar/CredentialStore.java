package autorequesttuar;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stores the TUAR login using the same file layout and encryption approach as
 * WorkOrderProms: User/login.properties beside the application, AES/CBC, and
 * a PBKDF2 key tied to the current Windows user and computer.
 */
final class CredentialStore {

    private static final String USER_FOLDER_NAME = "User";
    private static final String LOGIN_FILE_NAME = "login.properties";
    private static final String PASSWORD_PREFIX = "v1:";
    private static final byte[] KEY_SALT =
            "AutoRequestTUAR.Login.v1".getBytes(StandardCharsets.UTF_8);

    private final Path file;

    CredentialStore() {
        this(defaultFile());
    }

    CredentialStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Optional<SavedCredentials> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file.toFile())) {
            properties.load(input);
        }

        String username = properties.getProperty("username", "").trim();
        String protectedPassword = properties.getProperty("password", "");
        String loginType = normalizeLoginType(
                properties.getProperty("loginType", "HR"));
        String serverIp = properties.getProperty(
                "serverIp", TuarServer.DEFAULT_IP);
        if (username.isEmpty() || protectedPassword.isEmpty()) {
            return Optional.empty();
        }

        try {
            char[] password = decryptPassword(protectedPassword).toCharArray();
            return Optional.of(new SavedCredentials(
                    TuarServer.normalizeIp(serverIp),
                    username,
                    password,
                    loginType));
        } catch (Exception ex) {
            throw new IOException(
                    "Cannot read saved login. Please enter and save login again.",
                    ex);
        }
    }

    void save(
            String serverIp,
            String username,
            char[] password,
            String loginType) throws IOException {
        if (SharedAccount.isManaged()) {
            throw new IOException("Use the shared Toolkit account settings to save login details.");
        }
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("Cannot determine login folder.");
        }
        Files.createDirectories(parent);

        Properties properties = new Properties();
        properties.setProperty(
                "serverIp", TuarServer.normalizeIp(serverIp));
        properties.setProperty("username", username.trim());
        properties.setProperty("loginType", normalizeLoginType(loginType));
        try {
            properties.setProperty(
                    "password", encryptPassword(new String(password)));
        } catch (Exception ex) {
            throw new IOException("Cannot save password.", ex);
        }

        SettingsStore.writeProperties(file, properties, "AutoRequestTUAR login");
    }

    void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    Path getFile() {
        return file;
    }

    private static String encryptPassword(String password) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                createKey(),
                new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(
                password.getBytes(StandardCharsets.UTF_8));

        return PASSWORD_PREFIX
                + Base64.getEncoder().encodeToString(iv)
                + ":"
                + Base64.getEncoder().encodeToString(encrypted);
    }

    private static String decryptPassword(String protectedPassword)
            throws Exception {
        if (!protectedPassword.startsWith(PASSWORD_PREFIX)) {
            return protectedPassword;
        }

        String[] parts = protectedPassword
                .substring(PASSWORD_PREFIX.length())
                .split(":");
        if (parts.length != 2) {
            throw new IOException("Invalid saved password format.");
        }

        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] encrypted = Base64.getDecoder().decode(parts[1]);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                createKey(),
                new IvParameterSpec(iv));
        return new String(
                cipher.doFinal(encrypted),
                StandardCharsets.UTF_8);
    }

    private static SecretKeySpec createKey() throws Exception {
        String keyText = System.getProperty("user.name", "")
                + "|"
                + System.getenv("COMPUTERNAME")
                + "|"
                + System.getProperty("user.home", "");
        KeySpec spec = new PBEKeySpec(
                keyText.toCharArray(),
                KEY_SALT,
                12000,
                128);
        SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA256");
        } catch (Exception ex) {
            factory = SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA1");
        }
        return new SecretKeySpec(
                factory.generateSecret(spec).getEncoded(),
                "AES");
    }

    private static String normalizeLoginType(String loginType) {
        return "CLLS".equalsIgnoreCase(loginType == null
                ? "" : loginType.trim()) ? "CLLS" : "HR";
    }

    private static Path defaultFile() {
        return AppPaths.getAppFolder()
                .resolve(USER_FOLDER_NAME)
                .resolve(LOGIN_FILE_NAME);
    }

    static final class SavedCredentials implements AutoCloseable {

        private final String serverIp;
        private final String username;
        private final char[] password;
        private final String loginType;

        SavedCredentials(
                String serverIp,
                String username,
                char[] password,
                String loginType) {
            this.serverIp = serverIp;
            this.username = username;
            this.password = password;
            this.loginType = loginType;
        }

        String getUsername() {
            return username;
        }

        String getServerIp() {
            return serverIp;
        }

        char[] copyPassword() {
            return Arrays.copyOf(password, password.length);
        }

        String getLoginType() {
            return loginType;
        }

        @Override
        public void close() {
            Arrays.fill(password, '\0');
        }
    }
}
