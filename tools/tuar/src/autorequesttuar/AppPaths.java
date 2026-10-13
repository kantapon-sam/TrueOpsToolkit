package autorequesttuar;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

/**
 * Resolves files that must live beside the running application JAR.
 */
final class AppPaths {

    private AppPaths() {
    }

    static Path getAppFolder() {
        File location = getRunningLocation();
        if (location.isFile()) {
            File parent = location.getParentFile();
            if (parent != null) {
                return parent.toPath();
            }
        }

        return location.toPath();
    }

    static Path getAttachmentsFolder() {
        return getAppFolder()
                .resolve("attachments")
                .toAbsolutePath()
                .normalize();
    }

    static Path ensureAttachmentsFolder() throws IOException {
        Path folder = getAttachmentsFolder();
        Files.createDirectories(folder);
        if (!Files.isDirectory(folder)) {
            throw new IOException(
                    "Attachments path is not a folder: " + folder);
        }
        return folder;
    }

    static File getRunningLocation() {
        try {
            CodeSource source = AppPaths.class
                    .getProtectionDomain()
                    .getCodeSource();
            if (source != null) {
                return new File(source.getLocation().toURI())
                        .getCanonicalFile();
            }
        } catch (Exception ex) {
            System.out.println(
                    "Cannot detect jar folder: " + ex.toString());
        }

        try {
            return new File(".").getCanonicalFile();
        } catch (IOException ex) {
            return new File(
                    System.getProperty("user.dir", "."))
                    .getAbsoluteFile();
        }
    }

    static boolean isRunningFromJar() {
        File location = getRunningLocation();
        return location.isFile()
                && location.getName().toLowerCase(
                        java.util.Locale.ROOT).endsWith(".jar");
    }

    static String getJavaExecutable() {
        File javaHome = new File(
                System.getProperty("java.home", ""));
        File windowsJava = new File(javaHome, "bin\\java.exe");
        if (windowsJava.isFile()) {
            return windowsJava.getAbsolutePath();
        }
        File java = new File(javaHome, "bin\\java");
        return java.isFile() ? java.getAbsolutePath() : "java";
    }
}
