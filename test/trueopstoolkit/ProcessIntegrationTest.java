package trueopstoolkit;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Starts only the control window, never an approval/delete main. */
public final class ProcessIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path home=Paths.get(args[0]).toAbsolutePath();
        ProcessController first=new ProcessController(home),second=new ProcessController(home);
        Process contender=null;
        try {
            Process child=first.start(ModuleSpec.APPROVE,null);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!ProcessController.busy(home) && System.nanoTime()<deadline)Thread.sleep(50);
            check(child.isAlive() && ProcessController.busy(home),"module lock acquired");
            boolean rejected=false;
            try{second.start(ModuleSpec.REF,null);}catch(IOException expected){rejected=true;}
            check(rejected,"second controller rejects concurrent operation");
            contender=new ProcessBuilder(ToolkitPaths.childCommand(home,ModuleSpec.REF,null))
                .directory(ModuleSpec.REF.directory(home).toFile()).redirectErrorStream(true)
                .redirectOutput(home.resolve("logs/lock-contender-test.log").toFile()).start();
            check(contender.waitFor(12,TimeUnit.SECONDS),"contender exits");
            check(contender.exitValue()==3,"child-side lock prevents direct concurrent launch");
            // The PID handshake exists before a controlled stop is sent.
            Thread.sleep(400);
            first.stop();
            check(!child.isAlive(),"graceful stop closes module window");
            check(child.exitValue()==0,"graceful stop returns exit zero");
            check(!ProcessController.busy(home),"lock released after process exits");
            for(ModuleSpec m:ModuleSpec.values())check(ToolkitPaths.missing(home,m).isEmpty(),"packaged inputs ready: "+m.id);
            System.out.println("PROCESS INTEGRATION PASS");
        } finally {first.stop();if(contender!=null && contender.isAlive())contender.destroyForcibly();}
    }
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);System.out.println("PASS: "+name);}
}
