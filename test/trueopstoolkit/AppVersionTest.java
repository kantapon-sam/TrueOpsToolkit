package trueopstoolkit;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.jar.JarFile;

/** Verifies that all runnable artifacts agree with the one source version. */
public final class AppVersionTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Properties source = new Properties();
        try (InputStream input = Files.newInputStream(root.resolve("version.properties"))) { source.load(input); }
        String expected = source.getProperty("app.version");
        if (!expected.equals(AppVersion.current())) throw new AssertionError("Launcher version resource mismatch");
        Properties installed = new Properties();
        try (InputStream input = Files.newInputStream(root.resolve("dist/version.properties"))) { installed.load(input); }
        if (!expected.equals(installed.getProperty("app.version"))) throw new AssertionError("Installed updater version mismatch");
        check(root.resolve("dist/TrueOpsToolkit.jar"), expected, "trueopstoolkit.Main", false);
        for (ModuleSpec module : ModuleSpec.values()) {
            String shortcut;
            switch (module) {
                case APPROVE: shortcut = "Approve"; break;
                case REF: shortcut = "Ref"; break;
                default: shortcut = "Delete";
            }
            check(module.directory(root.resolve("dist")).resolve(module.jar), expected,
                    "trueopstoolkit.ModuleShortcuts$" + shortcut, true);
        }
        if (ModuleSpec.values().length != 3) throw new AssertionError("Toolkit must offer exactly three PROMS tools.");
        try {ModuleSpec.byId("tuar");throw new AssertionError("TUAR must not be a runnable Toolkit task.");}
        catch (IllegalArgumentException expectedRemoval) { }
        System.out.println("PASS: launcher and all three PROMS modules use version " + expected + "; TUAR is not a Toolkit task.");
    }

    private static void check(Path file, String expected, String main, boolean shortcut) throws Exception {
        try (JarFile jar = new JarFile(file.toFile())) {
            if (!expected.equals(jar.getManifest().getMainAttributes().getValue("Implementation-Version"))) {
                throw new AssertionError("JAR version mismatch: " + file.getFileName());
            }
            if (!main.equals(jar.getManifest().getMainAttributes().getValue("Main-Class"))) {
                throw new AssertionError("Unsupported standalone entry point: " + file.getFileName());
            }
            String classpath = jar.getManifest().getMainAttributes().getValue("Class-Path");
            if (shortcut && (classpath == null || !java.util.Arrays.asList(classpath.split(" ")).contains("../../TrueOpsToolkit.jar"))) {
                throw new AssertionError("Module shortcut must use the central launcher: " + file.getFileName());
            }
        }
    }
}
