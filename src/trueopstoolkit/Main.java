package trueopstoolkit;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.*;

public final class Main extends JFrame {
    private static FileChannel instanceChannel;
    private static FileLock instanceLock;
    final Path home;
    final ProcessController controller;
    final EnumMap<ModuleSpec,JButton> buttons=new EnumMap<ModuleSpec,JButton>(ModuleSpec.class);
    private final JLabel status=Ui.label("Ready • Choose a tool to get started",14,Ui.TEXT,false);
    private final JButton stop=Ui.button("Stop / Close tool",new Color(107,118,132));
    private final JButton openLog=new JButton("Latest log");
    private final JButton updateDriver=new JButton("Update ChromeDriver");
    private final JButton sharedAccount=new JButton("Shared account");
    private final JButton portalSettings=new JButton("Portal settings");
    private final JButton checkUpdate=new JButton("Check for updates");
    private final JLabel updateStatus=Ui.label("",12,Ui.MUTED,false);
    private ToolkitUpdateManager.PendingUpdate pendingUpdate;
    private boolean checkingUpdate;
    private boolean startupChecking;
    private boolean closeAfterUpdate;
    private boolean updatingDriver;
    private boolean installingUpdate;
    private boolean reopenedAfterUpdate;
    private final Path smokeReport;
    private final javax.swing.Timer refreshTimer;
    private final UpdateServices updates;
    private StartupUpdateDialog startupDialog;
    private UpdateHandoffDialog handoffDialog;
    private ModuleSpec requestedModule;
    Main(Path home,Path smokeReport) {
        this(home,smokeReport,new UpdateServices());
    }
    Main(Path home,Path smokeReport,UpdateServices updates) {
        super("TrueOps Toolkit v"+AppVersion.current());this.home=home;this.smokeReport=smokeReport;controller=new ProcessController(home);
        this.updates=updates;
        JPanel root=new JPanel(new BorderLayout());root.setBackground(Ui.BG);
        root.add(Ui.header("TrueOps Toolkit", "v"+AppVersion.current()+"  /  PROMS  /  One account for all four tools"),BorderLayout.NORTH);
        JPanel middle=new JPanel(new BorderLayout(0,17));middle.setOpaque(false);middle.setBorder(new EmptyBorder(22,26,18,26));
        JPanel title=new JPanel(new BorderLayout());title.setOpaque(false);title.add(Ui.label("Choose a tool",18,Ui.TEXT,true),BorderLayout.WEST);
        JPanel titleActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,10,0));titleActions.setOpaque(false);
        titleActions.add(Ui.label("One tool at a time",13,Ui.MUTED,false));
        sharedAccount.addActionListener(e -> SharedCredentialsDialog.showDialog(this,home));titleActions.add(sharedAccount);
        portalSettings.addActionListener(e -> PortalSettingsDialog.showDialog(this,home));titleActions.add(portalSettings);
        checkUpdate.addActionListener(e -> checkForUpdates());titleActions.add(checkUpdate);
        title.add(titleActions,BorderLayout.EAST);middle.add(title,BorderLayout.NORTH);
        JPanel grid=new JPanel(new GridLayout(0,1,0,12));grid.setOpaque(false);
        int number=0;
        for(ModuleSpec m:ModuleSpec.values()) {
            JPanel card=new JPanel(new BorderLayout(20,0));card.setBackground(Color.WHITE);
            card.setBorder(new CompoundBorder(new LineBorder(new Color(223,230,239)),new EmptyBorder(14,20,14,20)));
            JPanel details=new JPanel(new BorderLayout(0,6));details.setOpaque(false);
            JPanel cardHead=new JPanel(new BorderLayout(10,0));cardHead.setOpaque(false);
            cardHead.add(Ui.label(String.format("%02d",++number),23,m.color,true),BorderLayout.WEST);
            cardHead.add(Ui.label(m.title,19,Ui.TEXT,true),BorderLayout.CENTER);details.add(cardHead,BorderLayout.NORTH);
            JTextArea desc=Ui.note(m.description);
            desc.setFont(new Font("Tahoma",Font.PLAIN,14));details.add(desc,BorderLayout.CENTER);card.add(details,BorderLayout.CENTER);
            JButton b=Ui.button("Open "+m.title,m.color);b.setPreferredSize(new Dimension(235,42));b.addActionListener(e -> openModule(m));buttons.put(m,b);
            JPanel action=new JPanel(new GridBagLayout());action.setOpaque(false);action.add(b);card.add(action,BorderLayout.EAST);grid.add(card);
        }
        middle.add(grid,BorderLayout.CENTER);root.add(middle,BorderLayout.CENTER);
        JPanel footer=new JPanel(new BorderLayout(0,12));footer.setBorder(new EmptyBorder(0,26,20,26));footer.setOpaque(false);
        JPanel messages=new JPanel(new GridLayout(0,1,0,5));messages.setOpaque(false);messages.add(status);messages.add(updateStatus);
        footer.add(messages,BorderLayout.NORTH);JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));actions.setOpaque(false);
        JButton data=new JButton("Open app folder");data.addActionListener(e -> Ui.open(home,this));
        openLog.addActionListener(e -> {if(controller.log()!=null)Ui.open(controller.log(),this);});
        updateDriver.addActionListener(e -> updateDriver());
        stop.addActionListener(e -> stopWork());actions.add(data);actions.add(updateDriver);actions.add(openLog);actions.add(stop);footer.add(actions,BorderLayout.SOUTH);
        root.add(footer,BorderLayout.SOUTH);setContentPane(root);setSize(980,760);setMinimumSize(new Dimension(920,710));setLocationRelativeTo(null);setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        refreshTimer=new javax.swing.Timer(600,e -> refresh());refreshTimer.start();refresh();
        addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){requestClose();}});
        if(smokeReport==null){startupChecking=true;checkingUpdate=true;refresh();SwingUtilities.invokeLater(() -> startStartupUpdates());}
    }

    /** Injectable external operations keep lifecycle tests offline and prevent real installers from starting. */
    static class UpdateServices {
        ToolkitUpdateManager.PendingUpdate checkApp(Path home,Consumer<String> progress) throws Exception {
            return ToolkitUpdateManager.checkAndStage(home,AppVersion.current(),progress);
        }
        Path ensureDriver(Path home,Consumer<String> progress) throws Exception {return SharedChromeDriver.ensure(home,progress);}
        void install(ToolkitUpdateManager.PendingUpdate pending,Path home) throws IOException {ToolkitUpdateManager.launchInstaller(pending,home);}
        boolean offerUpdate(Main owner,ToolkitUpdateManager.PendingUpdate pending,boolean canInstallNow) {
            return StartupUpdateDialog.offerUpdate(owner,pending.getVersion(),canInstallNow);
        }
        void showStartupIssues(Main owner,String appIssue,String driverIssue) {StartupUpdateDialog.showIssues(owner,appIssue,driverIssue);}
        void showInstallFailure(Main owner,Throwable failure) {Ui.showError(owner,failure);}
    }
    private static final class StartupResult {
        ToolkitUpdateManager.PendingUpdate available;
        Exception appFailure,driverFailure;
    }
    private void requestClose() {
        if(closeAfterUpdate)return;
        if(updatingDriver){JOptionPane.showMessageDialog(this,"Please wait for the ChromeDriver update to finish before closing.");return;}
        if(controller.active()){JOptionPane.showMessageDialog(this,"Close the active tool or click Stop / Close tool before closing the launcher.");return;}
        if(checkingUpdate || startupChecking){
            closeAfterUpdate=true;if(startupDialog!=null)startupDialog.closing();refresh();return;
        }
        finishClose();
    }
    private void startStartupUpdates() {
        startupDialog=new StartupUpdateDialog(this,this::requestClose);
        if(closeAfterUpdate)startupDialog.closing();
        startupDialog.setVisible(true);
        new SwingWorker<StartupResult,Void>() {
            @Override protected StartupResult doInBackground() {
                StartupResult result=new StartupResult();
                try {
                    startupMessage(true,"Checking GitHub Releases...");
                    result.available=updates.checkApp(home,message -> startupMessage(true,message));
                    startupMessage(true,result.available==null?"Toolkit is up to date.":"Version "+result.available.getVersion()+" is downloaded and ready.");
                } catch(Exception failure){result.appFailure=failure;startupMessage(true,"Update check unavailable; the current version can still run.");}
                try {
                    startupMessage(false,"Checking Google Chrome and preparing the shared driver...");
                    ensureDriverSafely(message -> startupMessage(false,message));
                    startupMessage(false,"Shared ChromeDriver is ready for all four tools.");
                } catch(Exception failure){result.driverFailure=failure;startupMessage(false,"Driver preparation could not finish. You can retry from the launcher.");}
                return result;
            }
            @Override protected void done() {
                StartupResult result;
                try {result=get();}
                catch(Exception failure){result=new StartupResult();result.appFailure=failure;result.driverFailure=failure;}
                startupCompleted(result);
            }
        }.execute();
    }
    private void startupMessage(boolean toolkit,String message) {
        SwingUtilities.invokeLater(() -> {
            if(startupDialog!=null && startupDialog.isDisplayable()) {
                if(toolkit)startupDialog.toolkitStatus(message);else startupDialog.driverStatus(message);
            }
        });
    }
    private Path ensureDriverSafely(Consumer<String> progress) throws Exception {
        try(FileChannel channel=FileChannel.open(home.resolve(".active.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock lock;
            try {lock=channel.tryLock();}catch(OverlappingFileLockException busy){lock=null;}
            if(lock==null)throw new IOException("A tool is running. Close it before updating the shared driver.");
            try {return updates.ensureDriver(home,progress);}finally {lock.release();}
        }
    }
    private void startupCompleted(StartupResult result) {
        startupChecking=false;checkingUpdate=false;
        if(startupDialog!=null){startupDialog.dispose();startupDialog=null;}
        if(result.available!=null)pendingUpdate=result.available;
        boolean failed=result.appFailure!=null || result.driverFailure!=null;
        updateStatus.setText(failed?"Startup checks incomplete • Check your connection and retry from the launcher":
                pendingUpdate==null?"Toolkit and shared ChromeDriver are ready • Checked automatically at startup":
                "v"+pendingUpdate.getVersion()+" is ready • Shared ChromeDriver is ready");
        if(reopenedAfterUpdate)updateStatus.setText("Updated to v"+AppVersion.current()+" • "+updateStatus.getText());
        refresh();
        if(closeAfterUpdate){finishClose();return;}
        if(failed)updates.showStartupIssues(this,issue(result.appFailure),issue(result.driverFailure));
        if(pendingUpdate!=null)offerPendingUpdate();
        if(!closeAfterUpdate && isDisplayable() && requestedModule!=null){ModuleSpec requested=requestedModule;requestedModule=null;openModule(requested);}
    }
    private static String issue(Exception failure) {
        if(failure==null)return null;
        String message=failure.getMessage();return message==null?failure.getClass().getSimpleName():message;
    }
    private void checkForUpdates() {
        if(checkingUpdate || startupChecking || closeAfterUpdate)return;
        checkingUpdate=true;checkUpdate.setEnabled(false);updateStatus.setText("Checking GitHub Releases for updates...");
        new SwingWorker<ToolkitUpdateManager.PendingUpdate,Void>() {
            @Override protected ToolkitUpdateManager.PendingUpdate doInBackground() throws Exception {
                return updates.checkApp(home,message -> SwingUtilities.invokeLater(() -> updateStatus.setText(message)));
            }
            @Override protected void done() {
                ToolkitUpdateManager.PendingUpdate available=null;Exception failure=null;
                try {available=get();}catch(Exception ex){failure=ex;}
                updateCheckCompleted(available,failure);
            }
        }.execute();
    }
    void updateCheckCompleted(ToolkitUpdateManager.PendingUpdate available,Exception failure) {
        checkingUpdate=false;
        if(available!=null)pendingUpdate=available;
        if(failure==null)updateStatus.setText(pendingUpdate==null?"Up to date • Updates are checked automatically at startup":"v"+pendingUpdate.getVersion()+" will install when Toolkit closes • Your account and settings will be kept");
        else updateStatus.setText("Update check unavailable • You can keep working and check again later");
        refresh();
        if(closeAfterUpdate)finishClose();
        else if(available!=null)offerPendingUpdate();
    }
    private void offerPendingUpdate() {
        if(pendingUpdate!=null && updates.offerUpdate(this,pendingUpdate,canInstallNow()))finishClose();
    }
    private boolean canInstallNow() {
        if(controller.active() || updatingDriver || startupChecking || checkingUpdate || installingUpdate)return false;
        try {return !ProcessController.busy(home);}catch(IOException failure){return false;}
    }
    private void finishClose() {
        if(installingUpdate)return;
        if(!canInstallNow()) {
            closeAfterUpdate=false;refresh();updateStatus.setText("Close active tools before exiting or installing the Toolkit update.");return;
        }
        closeAfterUpdate=true;
        if(pendingUpdate!=null) {
            installingUpdate=true;refresh();
            updateStatus.setText("Installing v"+pendingUpdate.getVersion()+" • Toolkit will reopen automatically");
            if(startupDialog!=null){startupDialog.dispose();startupDialog=null;}
            handoffDialog=new UpdateHandoffDialog(this,pendingUpdate.getVersion());handoffDialog.setVisible(true);
            final ToolkitUpdateManager.PendingUpdate installing=pendingUpdate;
            new SwingWorker<Void,Void>() {
                @Override protected Void doInBackground() throws Exception {updates.install(installing,home);return null;}
                @Override protected void done() {
                    try {get();closeLauncher();}
                    catch(Exception failure) {
                        installingUpdate=false;closeAfterUpdate=false;
                        if(handoffDialog!=null){handoffDialog.dispose();handoffDialog=null;}
                        refresh();updateStatus.setText("Update could not start • The current Toolkit remains open; retry when ready");
                        updates.showInstallFailure(Main.this,failure.getCause()==null?failure:failure.getCause());
                    }
                }
            }.execute();
            return;
        }
        closeLauncher();
    }
    private void closeLauncher() {
        if(startupDialog!=null){startupDialog.dispose();startupDialog=null;}
        if(handoffDialog!=null){handoffDialog.dispose();handoffDialog=null;}
        refreshTimer.stop();dispose();
    }
    private void requestModule(ModuleSpec module){if(startupChecking){requestedModule=module;return;}openModule(module);}
    private void openModule(ModuleSpec m){if(closeAfterUpdate || startupChecking)return;try{controller.start(m,smokeReport);refresh();}catch(Exception ex){Ui.showError(this,ex);}}
    void refresh(){
        boolean active=controller.active(),external=false;
        try{external=!active && ProcessController.busy(home);}catch(IOException ignored){}
        for(JButton b:buttons.values())b.setEnabled(!active && !external && !updatingDriver && !closeAfterUpdate && !startupChecking);
        updateDriver.setEnabled(!active && !external && !updatingDriver && !closeAfterUpdate && !startupChecking);
        sharedAccount.setEnabled(!active && !external && !updatingDriver && !closeAfterUpdate && !startupChecking);
        portalSettings.setEnabled(!active && !external && !updatingDriver && !closeAfterUpdate && !startupChecking);
        checkUpdate.setEnabled(!checkingUpdate && !closeAfterUpdate && !startupChecking);
        stop.setEnabled(active);openLog.setEnabled(controller.log()!=null);
        if(installingUpdate){status.setText("Preparing installation • Please wait; Toolkit will reopen automatically");return;}
        if(closeAfterUpdate){status.setText("Closing after the update check and download finish...");return;}
        if(startupChecking){status.setText("Preparing Toolkit and shared ChromeDriver • Please wait...");return;}
        if(updatingDriver)return;
        if(active)status.setText("Open: "+controller.module().title+"  •  Close this tool before opening another");
        else if(external)status.setText("A tool is already open from another launcher window");
        else if(controller.current()!=null && controller.current().exitValue()!=0)status.setText("The tool stopped with an error • Open the latest log for details");
        else status.setText("Ready • Choose a tool to get started");
    }
    private void updateDriver(){
        if(closeAfterUpdate || startupChecking)return;
        updatingDriver=true;status.setText("Checking for shared ChromeDriver updates...");refresh();
        new SwingWorker<Path,Void>(){
            @Override protected Path doInBackground() throws Exception {
                return ensureDriverSafely(message -> SwingUtilities.invokeLater(() -> status.setText(message)));
            }
            @Override protected void done(){updatingDriver=false;refresh();try{get();JOptionPane.showMessageDialog(Main.this,"Shared ChromeDriver is ready for all four tools.\nUpdates are checked automatically before Chrome starts.");}catch(Exception ex){Ui.showError(Main.this,ex.getCause()==null?ex:ex.getCause());}}
        }.execute();
    }
    private void stopWork(){
        if(JOptionPane.showConfirmDialog(this,"Stop and close "+controller.module().title+"?","Stop tool",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        stop.setEnabled(false);new SwingWorker<Void,Void>(){
            @Override protected Void doInBackground() throws Exception{controller.stop();return null;}
            @Override protected void done(){try{get();}catch(Exception ex){Ui.showError(Main.this,ex);}refresh();}
        }.execute();
    }
    public static void main(String[] args) throws Exception {
        final Path home=ToolkitPaths.home();
        // Exit promptly while the helper installs, releasing this JAR instead of racing its file replacement.
        if(ToolkitUpdateManager.isInstallPending(home))return;
        Ui.setup();
        if(args.length==2 && "--ui-smoke".equals(args[0])){smoke(home,Paths.get(args[1]).toAbsolutePath());return;}
        instanceChannel=FileChannel.open(home.resolve(".launcher.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        instanceLock=instanceChannel.tryLock();
        if(instanceLock==null){JOptionPane.showMessageDialog(null,"TrueOps Toolkit is already open. Please use the existing window.");instanceChannel.close();return;}
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {try{instanceLock.release();instanceChannel.close();}catch(Exception ignored){}},"launcher-lock"));
        try(FileChannel channel=FileChannel.open(home.resolve(".active.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock migrationLock=channel.tryLock();
            if(migrationLock!=null)try{
                try {RetiredModules.archiveTuar(home);}
                catch(IOException failure){JOptionPane.showMessageDialog(null,"Could not finish archiving legacy TUAR files. The recovery data has been kept.\n"+failure.getMessage(),"Toolkit cleanup",JOptionPane.WARNING_MESSAGE);}
                SharedCredentialsMigration.migrate(home);SettingsFiles.migrate(home);
            }finally{migrationLock.release();}
        } catch(IOException ex){JOptionPane.showMessageDialog(null,"Could not migrate the existing account or settings. Open Shared account or tool settings to review them.\n"+ex.getMessage(),"Account and settings",JOptionPane.WARNING_MESSAGE);}
        final ModuleSpec requested=args.length==2 && "--module".equals(args[0])?ModuleSpec.byId(args[1]):null;
        final boolean reopened=args.length==2 && "--updated".equals(args[0]) && AppVersion.current().equals(args[1]);
        SwingUtilities.invokeLater(() -> {
            Main frame=new Main(home,null);frame.reopenedAfterUpdate=reopened;frame.setVisible(true);
            if(reopened)ToolkitUpdateManager.reportReopened(home,AppVersion.current());
            if(requested!=null)frame.requestModule(requested);
        });
    }
    private static void smoke(Path home,Path report) throws Exception {
        if(ProcessController.busy(home))throw new IOException("Close active module before UI tests.");
        Files.createDirectories(report);final Main[] holder=new Main[1];
        SwingUtilities.invokeAndWait(() -> {holder[0]=new Main(home,report);holder[0].setVisible(true);});
        Main frame=holder[0];StringBuilder summary=new StringBuilder("module\tui_opened\texit_code\n");
        try {
            SwingUtilities.invokeAndWait(() -> {try{Ui.snapshot(frame,report.resolve("launcher.png"),true);}catch(Exception ex){throw new RuntimeException(ex);}});
            for(ModuleSpec m:ModuleSpec.values()) {
                Path ready=report.resolve(m.id+".ready");Files.deleteIfExists(ready);
                SwingUtilities.invokeAndWait(() -> {frame.refresh();frame.buttons.get(m).doClick();});
                Process p=frame.controller.current();
                if(p==null)throw new IOException("Launch did not start: "+m.id);
                if(!p.waitFor(35,TimeUnit.SECONDS)){frame.controller.stop();throw new IOException("UI timed out: "+m.id);}
                if(p.exitValue()!=0 || !Files.isRegularFile(ready))throw new IOException("UI failed: "+m.id+". See "+frame.controller.log());
                summary.append(m.id).append("\tPASS\t").append(p.exitValue()).append('\n');
                System.out.println("UI PASS: "+m.id);
            }
            Files.write(report.resolve("ui-results.tsv"),summary.toString().getBytes(StandardCharsets.UTF_8));
        } finally {frame.controller.stop();SwingUtilities.invokeAndWait(() -> {frame.refreshTimer.stop();frame.dispose();});}
    }
}
