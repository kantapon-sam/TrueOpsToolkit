package trueopstoolkit;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Visible independent installer window, kept alive after the old launcher has closed. */
final class UpdaterProgressWindow implements ToolkitUpdater.Progress {
    private final JFrame frame = new JFrame("TrueOps Toolkit Update");
    private final JLabel status = new JLabel("Preparing the update...");
    private final JLabel detail = new JLabel("Toolkit will reopen automatically when installation finishes.");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton close = new JButton("Close");

    private UpdaterProgressWindow() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 15));
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        JLabel heading = new JLabel("Updating TrueOps Toolkit");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 19f));
        JPanel messages = new JPanel(new BorderLayout(0, 10));
        messages.add(status, BorderLayout.NORTH);
        progress.setStringPainted(true);
        messages.add(progress, BorderLayout.CENTER);
        detail.setForeground(new Color(70, 85, 105));
        messages.add(detail, BorderLayout.SOUTH);
        content.add(heading, BorderLayout.NORTH);
        content.add(messages, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new BorderLayout());
        close.setEnabled(false);
        close.addActionListener(event -> frame.dispose());
        buttons.add(close, BorderLayout.EAST);
        content.add(buttons, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setSize(650, 280);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    static ToolkitUpdater.Progress open() throws IOException {
        if (GraphicsEnvironment.isHeadless()) throw new IOException("The update progress window needs a desktop.");
        final UpdaterProgressWindow[] window = new UpdaterProgressWindow[1];
        onEdt(() -> window[0] = new UpdaterProgressWindow());
        return window[0];
    }

    @Override public void update(String message, int percent) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message);
            progress.setIndeterminate(percent < 10);
            progress.setValue(Math.max(0, Math.min(100, percent)));
        });
    }

    @Override public void failed(String message) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message.startsWith("TrueOps Toolkit was updated") ? "Update installed; reopening failed" : "Update could not finish");
            detail.setText("<html><div style='width: 440px'>" + message + "</div></html>");
            progress.setIndeterminate(false);
            progress.setString("Please check the update log");
            close.setEnabled(true);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.toFront();
        });
    }

    @Override public void close() { SwingUtilities.invokeLater(() -> frame.dispose()); }

    private static void onEdt(Runnable task) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) { task.run(); return; }
        try { SwingUtilities.invokeAndWait(task); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Update progress window was interrupted.", interrupted);
        } catch (InvocationTargetException failed) {
            throw new IOException("Update progress window could not be opened.", failed);
        }
    }
}
