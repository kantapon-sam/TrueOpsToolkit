package trueopstoolkit;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Exercises the actual module worker and progress timer with synthetic services only. */
public final class ModuleWindowProgressTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work=Files.createTempDirectory("trueops-module-progress-");
        String driverBefore=System.getProperty("webdriver.chrome.driver");
        try {
            for(ModuleSpec module:new ModuleSpec[]{ModuleSpec.APPROVE,ModuleSpec.REF,ModuleSpec.TK07}) {
                test(work.resolve(module.id+"-empty"),module,false);
                test(work.resolve(module.id+"-partial"),module,true);
            }
        } finally {
            restore("webdriver.chrome.driver",driverBefore);
            ToolkitUpdater.deleteTree(work);
        }
        System.out.println("PASS: module progress and final approval summaries ("+checks+" checks): both module keys, actual timer, zero approvals, partial failure and stale-progress prevention.");
    }

    private static void test(Path home,ModuleSpec module,boolean failure) throws Exception {
        Files.createDirectories(module.directory(home));
        Files.write(module.directory(home).resolve("settings.csv"),
                "Search,tk09\nDescription,Synthetic fixture only\n".getBytes(StandardCharsets.UTF_8));
        if(module==ModuleSpec.REF)Files.write(module.directory(home).resolve("Ref_No.csv"),
                "SYNTHETIC-REF,TK09,Synthetic fixture only\n".getBytes(StandardCharsets.UTF_8));
        String prefix="trueops."+module.id+".";
        Map<String,String> before=new HashMap<>();
        for(String suffix:new String[]{"progress","summary","result"}) {
            before.put(suffix,System.getProperty(prefix+suffix));
            System.setProperty(prefix+suffix,"Stale previous task value");
        }
        Services services=new Services(home,module,failure);
        ModuleWindow[] holder=new ModuleWindow[1];
        SwingUtilities.invokeAndWait(() -> {holder[0]=new ModuleWindow(home,module,services);holder[0].setVisible(true);});
        ModuleWindow frame=holder[0];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {method("startWork").invoke(frame);}catch(Exception error){throw new RuntimeException(error);}
            });
            check(services.entered.await(8,TimeUnit.SECONDS),"Synthetic worker entered");
            check(System.getProperty(prefix+"progress")==null,"New run clears old progress");
            check(System.getProperty(prefix+"summary")==null,"New run clears old summary");
            check(System.getProperty(prefix+"result")==null,"New run clears old result counts");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    check(((Timer)field("progressTimer").get(frame)).isRunning(),"Actual progress timer runs during work");
                    check(!button(frame,"start").isEnabled(),"Start disabled during work");
                    check(!button(frame,"save").isEnabled(),"Settings disabled during work");
                }catch(Exception error){throw new RuntimeException(error);}
            });
            services.publish.countDown();
            check(services.published.await(8,TimeUnit.SECONDS),"Worker published progress");
            waitUntil(() -> text(frame).equals(services.progress),"Actual timer displays published module progress");
            services.finish.countDown();
            waitUntil(() -> text(frame).contains(services.summary),"Worker completion displays actual summary counts");
            String finalText=text(frame);
            check(finalText.contains(failure?"approved: 1":"approved: 0"),"Final text preserves actual approved count");
            check(finalText.contains(failure?"failed: 1":"failed: 0"),"Final text preserves actual failure count");
            check(!finalText.contains("Process finished"),"Final status does not imply unverified success");
            if(failure)check(finalText.contains("The tool stopped with an error: Synthetic confirmation unavailable"),"Partial failure includes actionable error");
            else check(finalText.contains("Nothing was approved"),"Zero-match run explicitly states no approval");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    check(!((Timer)field("progressTimer").get(frame)).isRunning(),"Completion stops progress timer");
                    check(button(frame,"start").isEnabled(),"Completion restores Start");
                    check(button(frame,"save").isEnabled(),"Completion restores settings");
                    System.setProperty(prefix+"progress","Late stale running status");
                    method("pollWorkProgress").invoke(frame);
                    check(status(frame).getText().equals(finalText),"Late progress cannot overwrite final result");
                }catch(Exception error){throw new RuntimeException(error);}
            });
            check(services.accountChecks==1 && services.driverChecks==1 && services.runs==1,
                    "Worker used only injected fixture services exactly once");
        } finally {
            services.publish.countDown();services.finish.countDown();
            SwingUtilities.invokeAndWait(() -> {
                try {((Timer)field("progressTimer").get(frame)).stop();}catch(Exception ignored) { }
                frame.dispose();
            });
            for(Map.Entry<String,String> entry:before.entrySet())restore(prefix+entry.getKey(),entry.getValue());
        }
    }

    private static final class Services extends ModuleWindow.WorkServices {
        private final Path home;
        private final ModuleSpec expected;
        private final boolean failure;
        private final String progress,summary;
        private final CountDownLatch entered=new CountDownLatch(1),publish=new CountDownLatch(1),
                published=new CountDownLatch(1),finish=new CountDownLatch(1);
        private int accountChecks,driverChecks,runs;
        private Services(Path home,ModuleSpec module,boolean failure) {
            this.home=home;expected=module;this.failure=failure;
            progress="Synthetic progress for "+module.id+": scanning page 2 of 3.";
            summary=failure
                    ? "Synthetic partial result. Scanned: 2; matched: 2; approved: 1; skipped: 0; failed: 1."
                    : "No requests matched Search. Nothing was approved. Scanned: 3; matched: 0; approved: 0; skipped: 0; failed: 0.";
        }
        @Override void verifyAccount(Path candidate) {
            check(candidate.equals(home),"Account check uses isolated fixture home");accountChecks++;
        }
        @Override Path ensureDriver(Path candidate,Consumer<String> progress) {
            check(!SwingUtilities.isEventDispatchThread(),"Driver preparation runs off the EDT");
            check(candidate.equals(home),"Driver preparation uses fixture home");driverChecks++;
            return home.resolve("synthetic-unused-driver");
        }
        @Override void run(ModuleSpec module) throws Exception {
            check(!SwingUtilities.isEventDispatchThread(),"Module worker runs off the EDT");
            check(module==expected,"Worker runs requested module");runs++;entered.countDown();
            if(!publish.await(10,TimeUnit.SECONDS))throw new IOException("Synthetic progress wait timed out");
            System.setProperty("trueops."+module.id+".progress",progress);published.countDown();
            if(!finish.await(10,TimeUnit.SECONDS))throw new IOException("Synthetic completion wait timed out");
            System.setProperty("trueops."+module.id+".summary",summary);
            if(failure)throw new IOException("Synthetic confirmation unavailable");
        }
    }

    private static Field field(String name) throws Exception {Field value=ModuleWindow.class.getDeclaredField(name);value.setAccessible(true);return value;}
    private static Method method(String name) throws Exception {Method value=ModuleWindow.class.getDeclaredMethod(name);value.setAccessible(true);return value;}
    private static JButton button(ModuleWindow frame,String name) throws Exception {return (JButton)field(name).get(frame);}
    private static JTextArea status(ModuleWindow frame) throws Exception {return (JTextArea)field("status").get(frame);}
    private static String text(ModuleWindow frame) {
        String[] value=new String[1];
        try {SwingUtilities.invokeAndWait(() -> {try{value[0]=status(frame).getText();}catch(Exception error){throw new RuntimeException(error);}});}
        catch(Exception error){throw new RuntimeException(error);}
        return value[0];
    }
    private static void waitUntil(BooleanSupplier ready,String name) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!ready.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(20);
        check(ready.getAsBoolean(),name);
    }
    private static void restore(String key,String value) {if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    private static synchronized void check(boolean value,String name) {if(!value)throw new AssertionError(name);checks++;}
}
