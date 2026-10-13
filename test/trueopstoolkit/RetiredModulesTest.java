package trueopstoolkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Real filesystem fixtures only; no user requests, accounts, or release network access. */
public final class RetiredModulesTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("trueops-retired-test-");
        try {
            success(temporary.resolve("success"));
            failures(temporary.resolve("failures"));
            links(temporary.resolve("links"));
            System.out.println("RETIRED MODULES PASS (" + checks + " checks)");
        } finally { removeFixture(temporary, temporary); }
    }

    private static void success(Path fixture) throws Exception {
        Path home = fixture.resolve("installation"), backups = fixture.resolve("external-backups");
        Path tuar = home.resolve("modules/tuar");
        write(tuar.resolve("requests.xlsx"), new byte[] { 0, 1, 2, (byte) 255 });
        write(tuar.resolve("attachments/request-A/note.txt"), "synthetic attachment");
        write(tuar.resolve("User/login.properties"), "synthetic old account fixture");
        write(tuar.resolve("lib/old.jar"), "synthetic obsolete library");
        Files.createDirectories(tuar.resolve("empty/deep"));
        write(home.resolve("modules/approve/settings.csv"), "Search,keep\n");
        write(home.resolve("config/credentials.dat"), "synthetic encrypted fixture");
        write(fixture.resolve("separate-tuar/requests.xlsx"), "standalone application data");
        Path archive = RetiredModules.archiveTuar(home, backups, null);
        check(archive.startsWith(backups) && !archive.startsWith(home), "retired directory is archived outside installation");
        check(!Files.exists(tuar), "old modules/tuar folder is completely removed");
        check(Arrays.equals(new byte[] { 0, 1, 2, (byte) 255 }, Files.readAllBytes(archive.resolve("tuar/requests.xlsx"))), "workbook bytes preserved exactly");
        check(read(archive.resolve("tuar/attachments/request-A/note.txt")).equals("synthetic attachment"), "nested attachments preserved");
        check(read(archive.resolve("tuar/User/login.properties")).equals("synthetic old account fixture"), "old private application data preserved");
        check(read(archive.resolve("tuar/lib/old.jar")).equals("synthetic obsolete library"), "old program files preserved");
        check(Files.isDirectory(archive.resolve("tuar/empty/deep")), "empty directories preserved");
        check(Files.isRegularFile(archive.resolve("archive-info.properties")), "verified archive has recovery metadata");
        check(read(home.resolve("modules/approve/settings.csv")).equals("Search,keep\n") && read(home.resolve("config/credentials.dat")).equals("synthetic encrypted fixture"), "PROMS settings and shared account remain intact");
        check(read(fixture.resolve("separate-tuar/requests.xlsx")).equals("standalone application data"), "separate TUAR installation remains intact");
        check(RetiredModules.archiveTuar(home, backups, null) == null, "retirement is idempotent");
        write(tuar.resolve("new-note.txt"), "later legacy reappearance");
        Path second = RetiredModules.archiveTuar(home, backups, null);
        check(!second.equals(archive) && Files.isRegularFile(archive.resolve("tuar/requests.xlsx")), "later retirement never replaces an existing backup");
    }

    private static void failures(Path fixture) throws Exception {
        Path home = fixture.resolve("installation"), backups = fixture.resolve("backups"), tuar = home.resolve("modules/tuar");
        write(tuar.resolve("data.txt"), "original");
        expectFailure(() -> RetiredModules.archiveTuar(home, home.resolve("backups"), null));
        check(read(tuar.resolve("data.txt")).equals("original") && !Files.exists(home.resolve("backups")), "backup inside installation is refused before any removal");
        final Path[] recovery = new Path[1];
        expectFailure(() -> RetiredModules.archiveTuar(home, backups, (source, archive) -> { recovery[0] = archive; throw new IOException("synthetic failure"); }));
        check(read(tuar.resolve("data.txt")).equals("original") && read(recovery[0].resolve("tuar/data.txt")).equals("original"), "failure after verification preserves source and complete backup");
        expectFailure(() -> RetiredModules.archiveTuar(home, backups, (source, archive) -> { recovery[0] = archive; write(source.resolve("data.txt"), "edited during backup"); }));
        check(read(tuar.resolve("data.txt")).equals("edited during backup") && read(recovery[0].resolve("tuar/data.txt")).equals("original"), "concurrent source edit cancels removal and preserves both versions");
        Path invalid = fixture.resolve("invalid/modules/tuar");
        write(invalid, "not a directory");
        expectFailure(() -> RetiredModules.archiveTuar(fixture.resolve("invalid"), backups, null));
        check(read(invalid).equals("not a directory"), "non-directory TUAR path remains intact");
        Path unavailable = fixture.resolve("unavailable");
        write(unavailable, "not a backup directory");
        expectFailure(() -> RetiredModules.archiveTuar(home, unavailable, null));
        check(read(tuar.resolve("data.txt")).equals("edited during backup"), "unavailable backup location leaves original data intact");
    }

    private static void links(Path fixture) throws Exception {
        Path home = fixture.resolve("installation"), tuar = home.resolve("modules/tuar"), outside = fixture.resolve("outside"), backups = fixture.resolve("backups");
        write(tuar.resolve("real.txt"), "real TUAR fixture");
        write(outside.resolve("private.txt"), "external file must stay intact");
        Path link = tuar.resolve("linked-directory");
        if (!linkDirectory(link, outside)) throw new AssertionError("Cannot create a directory link fixture.");
        expectFailure(() -> RetiredModules.archiveTuar(home, backups, null));
        check(read(tuar.resolve("real.txt")).equals("real TUAR fixture") && read(outside.resolve("private.txt")).equals("external file must stay intact"), "nested junction/link is refused without following or deleting it");
        check(!Files.exists(backups), "unsafe source is rejected before a backup is created");
        Files.delete(link);
        Path backupLink = fixture.resolve("backup-link");
        if (!linkDirectory(backupLink, outside)) throw new AssertionError("Cannot create a backup directory link fixture.");
        expectFailure(() -> RetiredModules.archiveTuar(home, backupLink.resolve("nested"), null));
        check(Files.exists(tuar.resolve("real.txt")) && !Files.exists(outside.resolve("nested")), "backup ancestor junction/link is refused before creating files");
        Files.delete(backupLink);
        Files.delete(tuar.resolve("real.txt"));
        Files.delete(tuar);
        if (!linkDirectory(tuar, outside)) throw new AssertionError("Cannot create a root directory link fixture.");
        expectFailure(() -> RetiredModules.archiveTuar(home, backups, null));
        check(read(outside.resolve("private.txt")).equals("external file must stay intact"), "TUAR root junction/link cannot escape the installation boundary");
        Files.delete(tuar);
    }

    private static boolean linkDirectory(Path link, Path target) throws Exception {
        Files.createDirectories(link.getParent());
        try { Files.createSymbolicLink(link, target.toAbsolutePath()); return true; }
        catch (IOException | UnsupportedOperationException unavailable) {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) throw unavailable;
            String systemRoot = System.getenv("SystemRoot");
            Path powershell = Paths.get(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                    "$ErrorActionPreference='Stop';New-Item -ItemType Junction -Path $env:TRUEOPS_LINK_FIXTURE -Target $env:TRUEOPS_LINK_TARGET | Out-Null");
            builder.environment().put("TRUEOPS_LINK_FIXTURE", link.toAbsolutePath().toString());
            builder.environment().put("TRUEOPS_LINK_TARGET", target.toAbsolutePath().toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IOException("Link fixture command timed out.");
                return process.exitValue() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS);
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }

    private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected retirement failure"); }
        catch (IOException expected) { }
    }
    private static void write(Path path, String value) throws IOException { write(path, value.getBytes(StandardCharsets.UTF_8)); }
    private static void write(Path path, byte[] value) throws IOException { Files.createDirectories(path.getParent()); Files.write(path, value); }
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; System.out.println("PASS: " + label); }
    private static void removeFixture(Path root, Path file) throws IOException {
        if (!file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new IOException("Fixture cleanup escaped its root.");
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)
                && !Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(file)) { for (Path child : stream) removeFixture(root, child); }
        }
        Files.deleteIfExists(file);
    }
}
