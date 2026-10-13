package trueopstoolkit;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class ProcessController {
    private final Path home;
    private Process child;
    private ModuleSpec module;
    private Path log;
    private Path control;
    ProcessController(Path home){this.home=home;}
    synchronized Process current(){return child;}
    synchronized ModuleSpec module(){return module;}
    synchronized boolean active(){return child!=null && child.isAlive();}
    synchronized Path log(){return log;}
    static boolean busy(Path home) throws IOException {
        Files.createDirectories(home);
        try(FileChannel channel=FileChannel.open(home.resolve(".active.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            try {FileLock l=channel.tryLock(); if(l==null)return true; l.release(); return false;}
            catch(OverlappingFileLockException e){return true;}
        }
    }
    synchronized Process start(ModuleSpec m,Path smokeReport) throws Exception {
        if(active() || busy(home)) throw new IOException("A tool is already open. Close it before opening another.");
        List<String> missing=ToolkitPaths.missing(home,m);
        if(!missing.isEmpty()) throw new IOException("Missing files for "+m.title+": "+missing);
        Files.createDirectories(home.resolve("logs"));
        log=home.resolve("logs").resolve(new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date())+"-"+m.id+".log");
        control=home.resolve(".control").resolve(UUID.randomUUID().toString());
        Files.createDirectories(control.getParent());
        List<String> command=ToolkitPaths.childCommand(home,m,smokeReport);
        command.add(1,"-Dtrueops.control="+control.toString());
        ProcessBuilder pb=new ProcessBuilder(command);
        pb.directory(m.directory(home).toFile()); pb.redirectErrorStream(true); pb.redirectOutput(log.toFile());
        module=m; child=pb.start(); return child;
    }
    void stop() throws Exception {
        Process p; Path token; synchronized(this){p=child;token=control;} if(p==null || !p.isAlive())return;
        Path request=Paths.get(token.toString()+".stop"),pidFile=Paths.get(token.toString()+".pid");
        String ownPid=Files.isRegularFile(pidFile)?new String(Files.readAllBytes(pidFile),java.nio.charset.StandardCharsets.US_ASCII).trim():"";
        try {
            Files.write(request,new byte[]{1});
            // Let the child run its own WebDriver cleanup hooks first.
            if(p.waitFor(10,TimeUnit.SECONDS))return;
            if(p.isAlive()) {
                if(ownPid.isEmpty() && Files.isRegularFile(pidFile))ownPid=new String(Files.readAllBytes(pidFile),java.nio.charset.StandardCharsets.US_ASCII).trim();
                if(ownPid.matches("[0-9]+")) {
                    Process kill=new ProcessBuilder("taskkill.exe","/PID",ownPid,"/T","/F").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
                    if(!kill.waitFor(6,TimeUnit.SECONDS))kill.destroyForcibly();
                }
            }
            if(p.isAlive()){p.destroyForcibly();p.waitFor(4,TimeUnit.SECONDS);}
            if(p.isAlive())throw new IOException("The tool is still running. Please close its window manually.");
        } finally {Files.deleteIfExists(request);if(!p.isAlive())Files.deleteIfExists(pidFile);}
    }
}
