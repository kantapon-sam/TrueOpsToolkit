package trueopstoolkit;

import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Checks visible, responsive installation handoff and retry without running an installer. */
public final class MainInstallHandoffTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path work=Files.createTempDirectory("trueops-install-handoff-");
        try {test(work.resolve("success"),false);test(work.resolve("failure"),true);}
        finally {ToolkitUpdater.deleteTree(work);}
        System.out.println("PASS: installation handoff ("+checks+" checks): visible progress, responsive UI, one installer, failure recovery and retry.");
    }
    private static void test(Path home,boolean fail) throws Exception {
        Files.createDirectories(home);Services services=new Services();services.fail=fail;
        Main[] holder=new Main[1];
        SwingUtilities.invokeAndWait(() -> {holder[0]=new Main(home,home.resolve("smoke"),services);holder[0].setVisible(true);});
        Main frame=holder[0];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    field("pendingUpdate").set(frame,new ToolkitUpdateManager.PendingUpdate(home.resolve(".updates/release-test"),"9.9.9","synthetic","TrueOpsToolkit-9.9.9"));
                    frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));
                    check(frame.isDisplayable(),"Launcher stays visible during handoff");
                    check(dialog(frame).isShowing(),"Installation progress is visible before verification starts");
                    check(field("installingUpdate").getBoolean(frame),"Handoff is marked busy");
                } catch(Exception error){throw new RuntimeException(error);}
            });
            check(services.started.await(8,TimeUnit.SECONDS),"Background installer started");
            SwingUtilities.invokeAndWait(() -> {
                check(frame.isDisplayable(),"EDT remains responsive while installer preparation waits");
                for(javax.swing.JButton button:frame.buttons.values())check(!button.isEnabled(),"Tool launch stays disabled during installation");
                frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));
                check(services.installs.get()==1,"Repeated close does not start a second installer");
            });
            services.release.countDown();
            if(fail) {
                waitUntil(() -> services.errors.get()==1,"Installation failure reported");
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        check(frame.isDisplayable(),"Failed handoff keeps the current program open");
                        check(!field("closeAfterUpdate").getBoolean(frame),"Failed handoff clears close intent");
                        check(field("handoffDialog").get(frame)==null,"Failed handoff closes its progress dialog");
                        for(javax.swing.JButton button:frame.buttons.values())check(button.isEnabled(),"Failed handoff restores controls");
                        services.fail=false;frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));
                    } catch(Exception error){throw new RuntimeException(error);}
                });
            }
            waitUntil(() -> !frame.isDisplayable(),"Successful handoff closes launcher");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    check(field("handoffDialog").get(frame)==null,"Successful handoff disposes progress dialog");
                    check(!((Timer)field("refreshTimer").get(frame)).isRunning(),"Successful handoff stops launcher timer");
                    check(services.installs.get()==(fail?2:1),"Only requested installation attempts ran");
                } catch(Exception error){throw new RuntimeException(error);}
            });
        } finally {
            services.release.countDown();
            SwingUtilities.invokeAndWait(() -> {
                try {((Timer)field("refreshTimer").get(frame)).stop();JDialog dialog=dialog(frame);if(dialog!=null)dialog.dispose();}
                catch(Exception ignored) { }
                frame.dispose();
            });
        }
    }
    private static final class Services extends Main.UpdateServices {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger installs=new AtomicInteger(),errors=new AtomicInteger();volatile boolean fail;
        @Override void install(ToolkitUpdateManager.PendingUpdate pending,Path home) throws IOException {
            if(SwingUtilities.isEventDispatchThread())throw new AssertionError("Installer verification blocks the EDT");
            installs.incrementAndGet();started.countDown();
            try {if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Synthetic handoff timed out");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}
            if(fail)throw new IOException("Synthetic installer start failure");
        }
        @Override void showInstallFailure(Main owner,Throwable failure){errors.incrementAndGet();}
    }
    private static Field field(String name) throws Exception {Field value=Main.class.getDeclaredField(name);value.setAccessible(true);return value;}
    private static JDialog dialog(Main frame) throws Exception {return (JDialog)field("handoffDialog").get(frame);}
    private static void waitUntil(BooleanSupplier ready,String label) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!ready.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(15);
        check(ready.getAsBoolean(),label);
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}
