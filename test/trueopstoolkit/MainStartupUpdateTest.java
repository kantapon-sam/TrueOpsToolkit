package trueopstoolkit;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Real Swing lifecycle with synthetic update sources: no network, browsers, or installer processes. */
public final class MainStartupUpdateTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work=Files.createTempDirectory("trueops-startup-updates-");
        try {
            SwingUtilities.invokeAndWait(Ui::setup);
            testAutomaticChecksAndLater(work.resolve("automatic"));
            testIndependentFailures(work.resolve("failures"));
            testExternalOperation(work.resolve("external"));
            testCloseWhileDriverWorks(work.resolve("close"));
            testUpdateNowRace(work.resolve("race"));
            testSmokeSkipsChecksAndRealDialog(work.resolve("smoke"));
            System.out.println("PASS: startup updates ("+checks+" checks): both automatic checks, locking, independent failures, close cleanup, update choices, and smoke isolation.");
        } finally {ToolkitUpdater.deleteTree(work);}
    }

    private static void testAutomaticChecksAndLater(Path home) throws Exception {
        Files.createDirectories(home);FakeServices services=new FakeServices(home);services.available=true;services.holdApp=true;
        Main frame=create(home,null,services);
        try {
            await(services.appStarted,"Automatic toolkit check started");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    check(flag(frame,"startupChecking"),"Startup is marked busy");
                    check(((StartupUpdateDialog)value(frame,"startupDialog")).isVisible(),"Startup progress dialog is visible");
                    blockedControls(frame);
                } catch(Exception error){throw new RuntimeException(error);}
            });
            services.releaseApp.countDown();
            waitUntil(() -> services.offers.get()==1,"New toolkit version offered automatically");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    check(!flag(frame,"startupChecking"),"Startup flag clears after both checks");
                    check(value(frame,"startupDialog")==null,"Progress dialog is disposed after both checks");
                    check(frame.isDisplayable(),"Later leaves current session open");
                    check(services.installs.get()==0,"Later does not install immediately");
                    check(((JButton)value(frame,"sharedAccount")).isEnabled(),"Account editor becomes available");
                    check(services.appCalls.get()==1 && services.driverCalls.get()==1,"Both checks ran automatically once");
                    check(services.driverHadLock.get(),"Driver preparation owned the operation lock");
                    check(services.offerAllowed.get(),"Idle launcher offers Update now");
                    // The same manual-check completion callback must show the same new-version offer.
                    frame.updateCheckCompleted(services.pending,null);
                    check(services.offers.get()==2,"Manual check uses the new-version dialog too");
                    frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));
                    check(services.installs.get()==1,"Later installs on normal close");
                    check(!frame.isDisplayable(),"Normal close disposes the launcher");
                } catch(Exception error){throw new RuntimeException(error);}
            });
        } finally {services.releaseApp.countDown();cleanup(frame);}
    }

    private static void testIndependentFailures(Path home) throws Exception {
        for(boolean appFails:new boolean[]{true,false}) {
            Path caseHome=home.resolve(appFails?"toolkit-failure":"driver-failure");Files.createDirectories(caseHome);
            FakeServices services=new FakeServices(caseHome);services.failApp=appFails;services.failDriver=!appFails;
            Main frame=create(caseHome,null,services);
            try {
                waitUntil(() -> services.issueReports.get()==1,"Failure summary reported");
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        check(services.appCalls.get()==1 && services.driverCalls.get()==1,"One failure does not skip the other check");
                        check(frame.isDisplayable(),"Startup failure does not close the app");
                        check(!flag(frame,"startupChecking"),"Failure releases startup state");
                        check(((JButton)value(frame,"updateDriver")).isEnabled(),"Driver retry remains available after failure");
                        check(services.appIssue.get()!=null == appFails,"Toolkit failure is reported independently");
                        check(services.driverIssue.get()!=null != appFails,"Driver failure is reported independently");
                        check(!ProcessController.busy(caseHome),"Driver lock is released after success or failure");
                    } catch(Exception error){throw new RuntimeException(error);}
                });
            } finally {cleanup(frame);}
        }
    }

    private static void testExternalOperation(Path home) throws Exception {
        Files.createDirectories(home);FakeServices services=new FakeServices(home);
        try(FileChannel channel=FileChannel.open(home.resolve(".active.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileLock lock=channel.lock()) {
            Main frame=create(home,null,services);
            try {
                waitUntil(() -> services.issueReports.get()==1,"Busy operation warning reported");
                check(services.appCalls.get()==1,"Toolkit can still be checked while an external tool runs");
                check(services.driverCalls.get()==0,"A running operation prevents driver replacement");
                check(services.driverIssue.get()!=null,"Busy driver check explains why it was skipped");
            } finally {cleanup(frame);}
        }
    }

    private static void testCloseWhileDriverWorks(Path home) throws Exception {
        Files.createDirectories(home);FakeServices services=new FakeServices(home);services.available=true;services.holdDriver=true;
        Main frame=create(home,null,services);
        try {
            await(services.driverStarted,"Driver check started");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));
                    frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));
                    check(frame.isDisplayable(),"Close waits for driver cleanup");
                    check(flag(frame,"closeAfterUpdate"),"Close intent is remembered");
                    blockedControls(frame);
                    check(services.installs.get()==0,"Installer does not run while the driver lock is held");
                } catch(Exception error){throw new RuntimeException(error);}
            });
            services.releaseDriver.countDown();
            waitUntil(() -> services.installs.get()==1,"Deferred close installs after checks finish");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    check(!frame.isDisplayable(),"Deferred close disposes launcher");
                    check(value(frame,"startupDialog")==null,"Deferred close disposes progress dialog");
                    check(!((Timer)value(frame,"refreshTimer")).isRunning(),"Deferred close stops refresh timer");
                    check(services.offers.get()==0,"Closing does not show another update choice");
                    check(!ProcessController.busy(home),"Operation guard is released before installation");
                } catch(Exception error){throw new RuntimeException(error);}
            });
        } finally {services.releaseDriver.countDown();cleanup(frame);}
    }

    private static void testUpdateNowRace(Path home) throws Exception {
        Files.createDirectories(home);FakeServices services=new FakeServices(home);services.available=true;services.installNow=true;
        final FileChannel[] channel={null};final FileLock[] lock={null};
        services.beforeOfferReturns=() -> {
            try {
                channel[0]=FileChannel.open(home.resolve(".active.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                lock[0]=channel[0].lock();
            } catch(IOException failure){throw new RuntimeException(failure);}
        };
        Main frame=create(home,null,services);
        try {
            waitUntil(() -> services.offers.get()==1,"Update now offered");
            SwingUtilities.invokeAndWait(() -> {
                check(frame.isDisplayable(),"An operation that starts during the offer prevents shutdown");
                check(services.installs.get()==0,"Busy state is rechecked before installation");
            });
        } finally {
            if(lock[0]!=null)lock[0].release();if(channel[0]!=null)channel[0].close();cleanup(frame);
        }
    }

    private static void testSmokeSkipsChecksAndRealDialog(Path home) throws Exception {
        Files.createDirectories(home);FakeServices services=new FakeServices(home);Main frame=create(home,home.resolve("smoke"),services);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    check(services.appCalls.get()==0 && services.driverCalls.get()==0,"UI smoke performs no startup network work");
                    check(value(frame,"startupDialog")==null,"UI smoke opens no startup dialog");
                } catch(Exception error){throw new RuntimeException(error);}
            });
            for(boolean allowed:new boolean[]{true,false}) {
                AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
                SwingUtilities.invokeAndWait(() -> {
                    Timer dismiss=new Timer(100,null);dismiss.setRepeats(true);
                    dismiss.addActionListener(event -> {
                        for(Window window:Window.getWindows())if(window instanceof JDialog && window.isShowing()
                                && "TrueOps Toolkit update available".equals(((JDialog)window).getTitle())) {
                            dismiss.stop();
                            try {
                                JButton now=findButton((Container)window,"Update now"),later=findButton((Container)window,"Later");
                                check(now!=null && later!=null,"Update dialog has both required choices");
                                check(now.isEnabled()==allowed,"Update now is disabled while an operation is active");
                                later.doClick();
                            } catch(Throwable error){failure.set(error);window.dispose();}
                            break;
                        }
                    });
                    dismiss.start();
                    boolean selected=StartupUpdateDialog.offerUpdate(frame,"9.9.9",allowed);
                    dismiss.stop();check(!selected,"Later does not request installation");
                });
                if(failure.get()!=null)throw new AssertionError("Update choice dialog failed",failure.get());
            }
        } finally {cleanup(frame);}
    }

    private static final class FakeServices extends Main.UpdateServices {
        final Path home;final ToolkitUpdateManager.PendingUpdate pending;
        final AtomicInteger appCalls=new AtomicInteger(),driverCalls=new AtomicInteger(),offers=new AtomicInteger(),installs=new AtomicInteger(),issueReports=new AtomicInteger();
        final AtomicBoolean driverHadLock=new AtomicBoolean(),offerAllowed=new AtomicBoolean();
        final AtomicReference<String> appIssue=new AtomicReference<String>(),driverIssue=new AtomicReference<String>();
        final CountDownLatch appStarted=new CountDownLatch(1),driverStarted=new CountDownLatch(1),releaseApp=new CountDownLatch(1),releaseDriver=new CountDownLatch(1);
        volatile boolean available,failApp,failDriver,holdApp,holdDriver,installNow;
        volatile Runnable beforeOfferReturns;
        FakeServices(Path home){this.home=home;pending=new ToolkitUpdateManager.PendingUpdate(home.resolve(".updates/release-fixture"),"9.9.9","synthetic","TrueOpsToolkit-9.9.9");}
        @Override ToolkitUpdateManager.PendingUpdate checkApp(Path requested,Consumer<String> progress) throws Exception {
            if(SwingUtilities.isEventDispatchThread())throw new AssertionError("Toolkit check blocks the EDT");
            appCalls.incrementAndGet();appStarted.countDown();progress.accept("Synthetic toolkit check");
            if(holdApp && !releaseApp.await(10,TimeUnit.SECONDS))throw new IOException("Test toolkit gate timed out");
            if(failApp)throw new IOException("Synthetic offline toolkit feed");return available?pending:null;
        }
        @Override Path ensureDriver(Path requested,Consumer<String> progress) throws Exception {
            if(SwingUtilities.isEventDispatchThread())throw new AssertionError("Driver check blocks the EDT");
            driverCalls.incrementAndGet();driverHadLock.set(ProcessController.busy(home));driverStarted.countDown();progress.accept("Synthetic driver check");
            if(holdDriver && !releaseDriver.await(10,TimeUnit.SECONDS))throw new IOException("Test driver gate timed out");
            if(failDriver)throw new IOException("Synthetic driver preparation failure");return home.resolve("drivers/chromedriver.exe");
        }
        @Override boolean offerUpdate(Main owner,ToolkitUpdateManager.PendingUpdate pending,boolean allowed) {
            offerAllowed.set(allowed);if(beforeOfferReturns!=null)beforeOfferReturns.run();offers.incrementAndGet();return installNow;
        }
        @Override void install(ToolkitUpdateManager.PendingUpdate pending,Path home) throws IOException {
            if(ProcessController.busy(home))throw new AssertionError("Installer starts before operation lock release");installs.incrementAndGet();
        }
        @Override void showStartupIssues(Main owner,String app,String driver){appIssue.set(app);driverIssue.set(driver);issueReports.incrementAndGet();}
    }

    private static Main create(Path home,Path smoke,FakeServices services) throws Exception {
        Main[] frame={null};SwingUtilities.invokeAndWait(() -> {frame[0]=new Main(home,smoke,services);frame[0].setVisible(true);});return frame[0];
    }
    private static void blockedControls(Main frame) throws Exception {
        frame.refresh();for(JButton button:frame.buttons.values()){check(!button.isEnabled(),"Tool controls stay disabled while startup/close is pending");button.doClick();}
        check(frame.controller.current()==null,"Disabled startup controls do not launch a tool");
        for(String name:new String[]{"sharedAccount","updateDriver","checkUpdate"})check(!((JButton)value(frame,name)).isEnabled(),name+" remains disabled");
    }
    private static JButton findButton(Container parent,String text) {
        for(Component component:parent.getComponents()) {
            if(component instanceof JButton && text.equals(((JButton)component).getText()))return (JButton)component;
            if(component instanceof Container){JButton found=findButton((Container)component,text);if(found!=null)return found;}
        }
        return null;
    }
    private static Object value(Main frame,String name) throws Exception {Field field=Main.class.getDeclaredField(name);field.setAccessible(true);return field.get(frame);}
    private static boolean flag(Main frame,String name) throws Exception {return (Boolean)value(frame,name);}
    private static void cleanup(Main frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {try {((Timer)value(frame,"refreshTimer")).stop();for(Window owned:frame.getOwnedWindows())owned.dispose();frame.dispose();}catch(Exception error){throw new RuntimeException(error);}});
    }
    private static void await(CountDownLatch latch,String label) throws Exception {check(latch.await(10,TimeUnit.SECONDS),label);}
    private static void waitUntil(BooleanSupplier ready,String label) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!ready.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(25);
        check(ready.getAsBoolean(),label);SwingUtilities.invokeAndWait(() -> {});
    }
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
}
