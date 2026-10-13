package trueopstoolkit;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.swing.*;

public final class ModuleBootstrap {
    private static FileChannel channel;
    private static FileLock lock;
    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("Expected home and module ID");
        Ui.useEnglish();
        final Path home=Paths.get(args[0]).toAbsolutePath().normalize();
        final ModuleSpec module=ModuleSpec.byId(args[1]);
        channel=FileChannel.open(home.resolve(".active.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=channel.tryLock();
        if(lock==null){System.err.println("Another module is already open.");System.exit(3);return;}
        // Keep the lock until process termination, including all browser cleanup
        // hooks. The OS releases it even if this JVM crashes.
        installStopControl();
        System.setProperty("autorequesttuar.autorun","false");
        System.setProperty("autorequesttuar.update.disabled","true");
        System.setProperty("trueops.home",home.toString());
        System.setProperty("trueops.version",AppVersion.current());
        System.setProperty("webdriver.chrome.driver",SharedChromeDriver.path(home).toString());
        Class.forName(module.mainClass,false,ModuleBootstrap.class.getClassLoader());
        final boolean smoke=args.length==4 && "--ui-smoke".equals(args[2]);
        final Path report=smoke?Paths.get(args[3]):null;
        if(module==ModuleSpec.TUAR){invoke(module);}
        else {Ui.setup();SwingUtilities.invokeLater(() -> new ModuleWindow(home,module).setVisible(true));}
        if(smoke) SwingUtilities.invokeLater(() -> {
            final int[] count={0};
            Timer timer=new Timer(400,null);
            timer.addActionListener(e -> {
                count[0]++;
                for(Frame f:Frame.getFrames()) if(f.isShowing() && f instanceof JFrame &&
                    (module==ModuleSpec.TUAR ? f.getClass().getName().equals(module.mainClass) : f instanceof ModuleWindow)) {
                    timer.stop();
                    try {
                        Files.createDirectories(report);
                        Ui.snapshot((JFrame)f,report.resolve(module.id+".png"),true);
                        Files.write(report.resolve(module.id+".ready"),(module.id+"\n"+f.getTitle()+"\nmain-class-loaded\n").getBytes(StandardCharsets.UTF_8));
                        f.dispatchEvent(new WindowEvent(f,WindowEvent.WINDOW_CLOSING));
                        System.exit(0);
                    } catch(Exception ex){ex.printStackTrace();System.exit(1);}
                    return;
                }
                if(count[0]>50){timer.stop();System.err.println("Module window did not appear.");System.exit(2);}
            }); timer.start();
        });
    }
    private static void installStopControl() throws IOException {
        String control=System.getProperty("trueops.control");if(control==null)return;
        final Path pid=Paths.get(control+".pid"),stop=Paths.get(control+".stop");
        String runtime=java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        Files.write(pid,runtime.split("@")[0].getBytes(StandardCharsets.US_ASCII));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {try{Files.deleteIfExists(pid);Files.deleteIfExists(stop);}catch(Exception ignored){}},"control-cleanup"));
        java.util.Timer watcher=new java.util.Timer("stop-control",true);
        watcher.schedule(new java.util.TimerTask(){@Override public void run(){if(Files.isRegularFile(stop))System.exit(0);}},250,250);
    }
    static void invoke(ModuleSpec module) throws Exception {
        try {Class.forName(module.mainClass).getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
        catch(InvocationTargetException ex){Throwable cause=ex.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new RuntimeException(cause);}
    }
}
