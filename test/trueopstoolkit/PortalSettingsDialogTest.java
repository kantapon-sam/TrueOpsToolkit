package trueopstoolkit;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Exercises the real portal settings dialog against isolated synthetic settings. */
public final class PortalSettingsDialogTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("trueops-portal-dialog-");
        SwingUtilities.invokeAndWait(Ui::setup);
        try {
            saveAndCancel(directory.resolve("roundtrip"));
            repairInvalidConfig(directory.resolve("repair"));
            saveErrorKeepsDialogOpen(directory.resolve("file-error"));
            System.out.println("PASS: portal settings dialog (" + checks + " checks).");
        } finally {
            SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); });
            Path[] paths;
            try (Stream<Path> walk = Files.walk(directory)) { paths = walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new); }
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static void saveAndCancel(Path home) throws Exception {
        Session first = open(home);
        onEdt(() -> {
            equal(PortalSettings.Mode.EXTERNAL, mode(first.dialog).getSelectedItem(), "Default mode is visible");
            contains(text(first.dialog), PortalSettings.Mode.EXTERNAL.getLoginUrl(), "Default endpoint displayed");
            mode(first.dialog).setSelectedItem(PortalSettings.Mode.INTERNAL);
            contains(text(first.dialog), PortalSettings.Mode.INTERNAL.getLoginUrl(), "Mode change refreshes fixed endpoint");
            contains(text(first.dialog), "company network or VPN", "Internal network requirement displayed");
            button(first.dialog, "Save portal mode").doClick();
        });
        equal(true, first.finish(), "Save returns changed=true");
        equal(PortalSettings.Mode.INTERNAL, PortalSettings.load(home), "UI saved shared Internal mode");
        byte[] saved = Files.readAllBytes(home.resolve("config/portal.properties"));
        Session second = open(home);
        onEdt(() -> {
            equal(PortalSettings.Mode.INTERNAL, mode(second.dialog).getSelectedItem(), "Saved mode restored in dialog");
            mode(second.dialog).setSelectedItem(PortalSettings.Mode.EXTERNAL);
            button(second.dialog, "Cancel").doClick();
        });
        equal(false, second.finish(), "Cancel returns changed=false");
        equal(true, Arrays.equals(saved, Files.readAllBytes(home.resolve("config/portal.properties"))), "Cancel leaves saved configuration unchanged");
    }

    private static void repairInvalidConfig(Path home) throws Exception {
        Path file = home.resolve("config/portal.properties");
        Files.createDirectories(file.getParent());
        Files.write(file, "portal.mode=UNKNOWN\n".getBytes(StandardCharsets.UTF_8));
        Session session = open(home);
        onEdt(() -> {
            contains(text(session.dialog), "save to repair it", "Invalid-mode recovery remains actionable");
            equal(true, button(session.dialog, "Save portal mode").isEnabled(), "Repair Save stays enabled");
            mode(session.dialog).setSelectedItem(PortalSettings.Mode.INTERNAL);
            button(session.dialog, "Save portal mode").doClick();
        });
        equal(true, session.finish(), "Repair save completes");
        equal(PortalSettings.Mode.INTERNAL, PortalSettings.load(home), "Invalid setting replaced with a valid fixed mode");
    }

    private static void saveErrorKeepsDialogOpen(Path home) throws Exception {
        Files.createDirectories(home);
        Files.write(home.resolve("config"), new byte[] {1});
        Session session = open(home);
        SwingUtilities.invokeLater(() -> button(session.dialog, "Save portal mode").doClick());
        JDialog error = awaitDialog("TrueOps Toolkit");
        onEdt(() -> {
            equal(true, session.dialog.isShowing(), "Save error keeps settings dialog available");
            error.dispose();
        });
        onEdt(() -> button(session.dialog, "Cancel").doClick());
        equal(false, session.finish(), "Failed save never reports success");
        equal(true, Files.isRegularFile(home.resolve("config")), "Failed save preserves original conflicting file");
    }

    private static Session open(Path home) throws Exception {
        Session session = new Session();
        SwingUtilities.invokeLater(() -> {
            try { session.saved = PortalSettingsDialog.showDialog(new JPanel(), home); }
            catch (Throwable failure) { session.failure.set(failure); }
            finally { session.finished.countDown(); }
        });
        session.dialog = awaitDialog("Portal settings | TrueOps Toolkit");
        return session;
    }

    private static JDialog awaitDialog(String title) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < until) {
            AtomicReference<JDialog> found = new AtomicReference<>();
            onEdt(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog && window.isShowing() && title.equals(((JDialog) window).getTitle())) found.set((JDialog) window);
                }
            });
            if (found.get() != null) return found.get();
            Thread.sleep(30);
        }
        throw new AssertionError("Expected visible dialog: " + title);
    }

    @SuppressWarnings("unchecked") private static JComboBox<PortalSettings.Mode> mode(JDialog dialog) {
        for (Component component : components(dialog.getContentPane())) if (component instanceof JComboBox) return (JComboBox<PortalSettings.Mode>) component;
        throw new AssertionError("Portal mode combo box missing.");
    }

    private static JButton button(JDialog dialog,String label) {
        for (Component component : components(dialog.getContentPane())) if (component instanceof JButton && label.equals(((JButton) component).getText())) return (JButton) component;
        throw new AssertionError("Expected button: " + label);
    }

    private static String text(JDialog dialog) {
        StringBuilder content = new StringBuilder();
        for (Component component : components(dialog.getContentPane())) if (component instanceof JTextArea) content.append(((JTextArea) component).getText()).append('\n');
        return content.toString();
    }

    private static List<Component> components(Container parent) {
        List<Component> list = new ArrayList<>();
        for (Component component : parent.getComponents()) {
            list.add(component);
            if (component instanceof Container) list.addAll(components((Container) component));
        }
        return list;
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void contains(String actual,String expected,String label) {
        if (!actual.contains(expected)) throw new AssertionError(label);
        checks++;
    }
    private static void equal(Object expected,Object actual,String label) {
        if (!expected.equals(actual)) throw new AssertionError(label);
        checks++;
    }

    private static final class Session {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean saved;
        JDialog dialog;
        boolean finish() throws Exception {
            if (!finished.await(6, TimeUnit.SECONDS)) throw new AssertionError("Portal settings dialog did not finish.");
            if (failure.get() != null) throw new AssertionError("Portal dialog failed.", failure.get());
            return saved;
        }
    }
}
