package trueopstoolkit;

import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Exercises the close/completion race without downloading or launching an installer. */
public final class MainUpdateCloseTest {
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("trueops-update-close-test-");
        try {
            SwingUtilities.invokeAndWait(() -> {
                verify(home,null);
                verify(home,new IOException("Synthetic offline check"));
            });
            System.out.println("PASS: close during update waits, blocks new work, and closes after success or failure.");
        } finally {ToolkitUpdater.deleteTree(home);}
    }

    private static void verify(Path home,Exception outcome) {
        // A smoke-report path suppresses live startup checks; completion follows the real UI callback.
        Main frame=new Main(home,home.resolve("smoke"));
        try {
            frame.setVisible(true);
            field("checkingUpdate").setBoolean(frame,true);
            frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));
            if(!frame.isDisplayable())throw new AssertionError("Closed before staging completed.");
            if(!field("closeAfterUpdate").getBoolean(frame))throw new AssertionError("Close intent was lost.");
            frame.refresh(); // The periodic refresh must not re-enable work during shutdown.
            for(JButton button:frame.buttons.values()) {
                if(button.isEnabled())throw new AssertionError("A module can start while closing.");
                button.doClick();
            }
            if(frame.controller.current()!=null)throw new AssertionError("Close requested but a module started.");
            for(String name:new String[]{"sharedAccount","updateDriver","checkUpdate"}) {
                if(((JButton)field(name).get(frame)).isEnabled())throw new AssertionError(name+" was enabled while closing.");
            }
            // Repeated close requests neither discard staging nor open another confirmation dialog.
            frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));
            if(!frame.isDisplayable())throw new AssertionError("Repeated close discarded active staging.");
            frame.updateCheckCompleted(null,outcome);
            if(frame.isDisplayable())throw new AssertionError("Window did not close after check completion.");
            if(((Timer)field("refreshTimer").get(frame)).isRunning())throw new AssertionError("Refresh timer keeps the launcher alive.");
        } catch(Exception error){throw new RuntimeException(error);}
        finally {
            try {((Timer)field("refreshTimer").get(frame)).stop();}catch(Exception ignored){}
            frame.dispose();
        }
    }

    private static Field field(String name) throws Exception {
        Field field=Main.class.getDeclaredField(name);field.setAccessible(true);return field;
    }
}
