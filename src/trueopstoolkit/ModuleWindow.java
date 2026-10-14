package trueopstoolkit;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;

final class ModuleWindow extends JFrame {
    private final Path home;
    private final ModuleSpec module;
    private final ModuleSettings settings;
    private final WorkServices workServices;
    private final JTextArea status=Ui.note("");
    private final JTextField username=new JTextField(26),search=new JTextField(26),description=new JTextField(26),approvalDescription=new JTextField(26),rejectionDescription=new JTextField(26);
    private final JTextField searchSecond=new JTextField(26),searchThird=new JTextField(26),searchFourth=new JTextField(26);
    private final JButton sharedAccount=new JButton("Shared account settings");
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JPanel editor=new JPanel(new BorderLayout());
    private final JButton start,check,save,reload;
    private boolean running,dirty,loading,loaded;
    private boolean monitorWorkProgress;
    private final javax.swing.Timer progressTimer=new javax.swing.Timer(250,event -> pollWorkProgress());

    ModuleWindow(Path home,ModuleSpec module) {
        this(home,module,new WorkServices());
    }

    ModuleWindow(Path home,ModuleSpec module,WorkServices workServices) {
        super(module.title+" | TrueOps Toolkit v"+AppVersion.current());this.home=home;this.module=module;this.workServices=workServices;settings=new ModuleSettings(home,module);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel root=new JPanel(new BorderLayout());root.setBackground(Ui.BG);
        root.add(Ui.header(module.title,"TrueOps Toolkit v"+AppVersion.current()+" • One shared account for every tool"),BorderLayout.NORTH);
        JPanel body=new JPanel(new BorderLayout(0,12));body.setOpaque(false);body.setBorder(new EmptyBorder(16,22,18,22));
        JTabbedPane tabs=new JTabbedPane();
        JPanel form=new JPanel(new GridBagLayout());form.setBorder(new EmptyBorder(12,14,12,14));
        int row=0;
        username.setEditable(false);
        addRow(form,row++,"Shared account",username);
        sharedAccount.addActionListener(e -> {SharedCredentialsDialog.showDialog(this,home);refreshAccount();});
        addRow(form,row++,"",sharedAccount);
        JButton portal=new JButton("Portal settings (External / Internal)");portal.addActionListener(event->PortalSettingsDialog.showDialog(this,home));addRow(form,row++,"Portal",portal);
        if(module==ModuleSpec.APPROVE || module==ModuleSpec.TK07) {
            JTextField[] criteria={search,searchSecond,searchThird,searchFourth};int count=module==ModuleSpec.APPROVE?4:2;
            JPanel conditions=new JPanel(new GridLayout(count==4?2:1,2,12,8));
            for(int index=0;index<count;index++){JPanel condition=new JPanel(new BorderLayout(0,4));condition.add(Ui.label("Condition "+(index+1),12,Ui.TEXT,false),BorderLayout.NORTH);condition.add(criteria[index],BorderLayout.CENTER);conditions.add(condition);}
            addRow(form,row++,"Search (AND)",conditions);
            if(module==ModuleSpec.TK07){addRow(form,row++,"Approval comment",approvalDescription);addRow(form,row++,"Rejection comment",rejectionDescription);}
            else addRow(form,row++,"Description",description);
            addRow(form,row++,"",Ui.note("Fill at least one condition. Every filled condition must match; letter case is ignored.\nTK codes (e.g. TK09) match Request Type only. Enter one term per field."));
        }
        if(module!=ModuleSpec.DELETE)addRow(form,row++,"",Ui.note("The tool waits for the page and each search result to finish loading automatically."));
        addRow(form,row++,"",Ui.note("Your shared username and password are saved without encryption.\nSign in is automatic. Complete CAPTCHA if the selected portal requests it."));
        GridBagConstraints spacer=new GridBagConstraints();spacer.gridy=row;spacer.weighty=1;form.add(new JPanel(),spacer);
        tabs.addTab("Login / Settings",new JScrollPane(form));
        String[] columns=module==ModuleSpec.REF?new String[]{"Ref No.","Type","Description"}:new String[]{"Ref No.","Deletion reason"};
        tableModel=new DefaultTableModel(columns,0);table=new JTable(tableModel);table.setRowHeight(28);table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost",Boolean.TRUE);
        if(settings.hasReferences()) {
            JPanel references=new JPanel(new BorderLayout(0,8));references.setBorder(new EmptyBorder(10,10,10,10));
            references.add(Ui.note("Edit cells directly • Changes are saved to this tool's Ref_No.csv.\n"+(module==ModuleSpec.REF?"Ref No. and Type ignore letter case. Both are required; Description is optional.":"Ref No. and a deletion reason are required in every row.")),BorderLayout.NORTH);
            references.add(new JScrollPane(table),BorderLayout.CENTER);
            JPanel rowActions=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));JButton add=new JButton("Add row"),remove=new JButton("Remove selected rows");
            add.addActionListener(e -> {finishEditing();tableModel.addRow(new String[tableModel.getColumnCount()]);int last=tableModel.getRowCount()-1;table.setRowSelectionInterval(last,last);table.scrollRectToVisible(table.getCellRect(last,0,true));});
            remove.addActionListener(e -> {finishEditing();int[] selected=table.getSelectedRows();for(int i=selected.length-1;i>=0;i--)tableModel.removeRow(table.convertRowIndexToModel(selected[i]));});
            rowActions.add(add);rowActions.add(remove);references.add(rowActions,BorderLayout.SOUTH);tabs.addTab("Records",references);
        }
        editor.add(tabs,BorderLayout.CENTER);body.add(editor,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(0,8));bottom.setOpaque(false);
        status.setRows(4);status.setBorder(new EmptyBorder(8,10,8,10));bottom.add(new JScrollPane(status),BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));actions.setOpaque(false);
        reload=new JButton("Reload saved settings");reload.addActionListener(e -> {if(discardEdits())loadSettings();});
        save=new JButton("Save settings");save.addActionListener(e -> saveSettings(false));
        check=new JButton("Update / test Chrome");check.addActionListener(e -> checkBrowser());
        start=Ui.button("Start "+module.title,module.color);start.addActionListener(e -> startWork());
        actions.add(reload);actions.add(save);actions.add(check);actions.add(start);bottom.add(actions,BorderLayout.SOUTH);
        JPanel utilities=new JPanel(new BorderLayout(8,0));utilities.setOpaque(false);
        utilities.add(Ui.label("Shared ChromeDriver • Updates checked before each use",12,Ui.MUTED,false),BorderLayout.CENTER);
        JButton folder=new JButton("Open data folder");folder.addActionListener(e -> Ui.open(module.directory(home),this));utilities.add(folder,BorderLayout.EAST);
        bottom.add(utilities,BorderLayout.NORTH);body.add(bottom,BorderLayout.SOUTH);root.add(body,BorderLayout.CENTER);setContentPane(root);
        setSize(980,710);setMinimumSize(new Dimension(920,630));setLocationRelativeTo(null);
        DocumentListener changes=new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}};
        search.getDocument().addDocumentListener(changes);description.getDocument().addDocumentListener(changes);
        searchSecond.getDocument().addDocumentListener(changes);searchThird.getDocument().addDocumentListener(changes);searchFourth.getDocument().addDocumentListener(changes);
        approvalDescription.getDocument().addDocumentListener(changes);rejectionDescription.getDocument().addDocumentListener(changes);
        tableModel.addTableModelListener(e -> changed());loadSettings();
        addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){
            if(running && JOptionPane.showConfirmDialog(ModuleWindow.this,"A task is running. Stop it and close this window?","Stop tool",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            if(!running && !discardEdits())return;
            progressTimer.stop();dispose();System.exit(0);
        }});
    }

    private static void addRow(JPanel panel,int row,String label,Component field) {
        GridBagConstraints c=new GridBagConstraints();c.gridy=row;c.gridx=0;c.anchor=GridBagConstraints.NORTHWEST;c.insets=new Insets(8,4,8,14);
        panel.add(Ui.label(label,14,Ui.TEXT,false),c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;panel.add(field,c);
    }
    private void changed(){if(!loading){dirty=true;save.setText("Save settings *");}}
    private void clean(){dirty=false;save.setText("Save settings");}
    private boolean discardEdits(){finishEditing();return !dirty || JOptionPane.showConfirmDialog(this,"Discard your unsaved changes?","Unsaved changes",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION;}
    private void loadSettings() {
        loading=true;
        try {
            settings.load();refreshAccount();String[] terms=settings.search.split("&&",module==ModuleSpec.TK07?2:4);JTextField[] criteria={search,searchSecond,searchThird,searchFourth};for(int index=0;index<criteria.length;index++)criteria[index].setText(index<terms.length?terms[index].trim():"");description.setText(settings.description);approvalDescription.setText(settings.approvalDescription);rejectionDescription.setText(settings.rejectionDescription);
            tableModel.setRowCount(0);for(String[] row:settings.rows)tableModel.addRow(row);
            loaded=true;clean();status.setText("Ready to edit • Save settings to keep your changes.\nStart saves the current settings and runs "+module.title+".");
        } catch(Exception ex) {loaded=false;status.setText("Could not load settings: "+ex.getMessage()+"\nCheck the files in the data folder, then click Reload saved settings.");}
        finally {loading=false;busy(false);}
    }
    private void finishEditing(){if(table.isEditing())table.getCellEditor().stopCellEditing();}
    private void collect() throws IOException {
        finishEditing();
        if(module==ModuleSpec.APPROVE || module==ModuleSpec.TK07){java.util.List<String> terms=new java.util.ArrayList<>();JTextField[] criteria={search,searchSecond,searchThird,searchFourth};int count=module==ModuleSpec.APPROVE?4:2;for(int index=0;index<count;index++){String term=criteria[index].getText().trim();if(term.contains("&&"))throw new IOException("Enter each search condition in a separate field.");if(!term.isEmpty())terms.add(term);}settings.search=String.join("&&",terms);}
        settings.description=description.getText();settings.approvalDescription=approvalDescription.getText();settings.rejectionDescription=rejectionDescription.getText();settings.rows.clear();
        for(int r=0;r<tableModel.getRowCount();r++) {
            String[] row=new String[tableModel.getColumnCount()];
            for(int c=0;c<row.length;c++){Object cell=tableModel.getValueAt(r,c);row[c]=cell==null?"":cell.toString();if(c<row.length-1)row[c]=row[c].trim();}
            settings.rows.add(row);
        }
    }
    private boolean saveSettings(boolean forRun) {
        if(!loaded)return false;
        try {collect();settings.validate(forRun);settings.save();clean();status.setText("Settings and records saved.");return true;}
        catch(Exception ex){Ui.showError(this,ex);return false;}
    }
    private void busy(boolean value) {
        running=value;enableTree(editor,!value && loaded);save.setEnabled(!value && loaded);reload.setEnabled(!value);start.setEnabled(!value && loaded);check.setEnabled(!value);
    }
    private void refreshAccount() {
        try {java.util.Optional<SharedCredentials.Credentials> saved=SharedCredentials.load(home);
            if(saved.isPresent())try(SharedCredentials.Credentials account=saved.get()){username.setText(account.getUsername());}
            else username.setText("No shared account configured");
        } catch(IOException ex){username.setText("Cannot read account. Open Shared account settings.");}
    }
    private static void enableTree(Component component,boolean enabled){component.setEnabled(enabled);if(component instanceof Container)for(Component child:((Container)component).getComponents())enableTree(child,enabled);}
    private void updateStatus(String message){SwingUtilities.invokeLater(() -> status.setText(message));}
    private void pollWorkProgress() {
        if(!monitorWorkProgress || !running)return;
        String progress=System.getProperty("trueops."+module.id+".progress","");
        if(!progress.isEmpty())status.setText(progress);
    }
    private String runSummary() {return System.getProperty("trueops."+module.id+".summary","");}
    private void checkBrowser() {
        busy(true);status.setText("Checking for shared ChromeDriver updates...");
        new SwingWorker<String,Void>(){
            @Override protected String doInBackground() throws Exception {
                Path driver=SharedChromeDriver.ensure(home,ModuleWindow.this::updateStatus);
                updateStatus("Testing Chrome with a local test page...");
                return BrowserCheck.run(driver,null,home.resolve("logs/browser-checks/"+module.id),false);
            }
            @Override protected void done(){busy(false);try{get();status.setText("Shared ChromeDriver is ready. Chrome opened and closed the test page successfully.");}catch(Exception ex){status.setText("Browser test failed: "+ex.getCause());}}
        }.execute();
    }
    private void startWork() {
        try{collect();settings.validate(true);}catch(Exception ex){Ui.showError(this,ex);return;}
        try {workServices.verifyAccount(home);}catch(IOException ex){Ui.showError(this,ex);return;}
        if(module==ModuleSpec.DELETE && JOptionPane.showConfirmDialog(this,"Delete the "+settings.rows.size()+" sites listed in this table?\nReview every reference and deletion reason before confirming.","Start Delete Site",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
        if(!saveSettings(true))return;
        for(String suffix:new String[]{"progress","summary","result"})System.clearProperty("trueops."+module.id+"."+suffix);
        monitorWorkProgress=true;progressTimer.start();
        busy(true);status.setText("Checking shared ChromeDriver before starting...");
        new SwingWorker<Void,Void>(){
            @Override protected Void doInBackground() throws Exception {
                Path driver=workServices.ensureDriver(home,ModuleWindow.this::updateStatus);
                System.setProperty("webdriver.chrome.driver",driver.toString());
                updateStatus("Running • Signing in through the Chrome window.\nClick Latest log in the launcher for more details.");
                workServices.run(module);return null;
            }
            @Override protected void done(){
                monitorWorkProgress=false;progressTimer.stop();busy(false);
                String summary=runSummary();
                try {
                    get();
                    status.setText((summary.isEmpty()?"Process ended. Check PROMS and the log to verify the results.":summary)+"\nYou can edit the settings before the next run.");
                }catch(Exception ex){
                    Throwable cause=ex.getCause()==null?ex:ex.getCause();
                    status.setText((summary.isEmpty()?"":summary+"\n")+"The tool stopped with an error: "+cause.getMessage()+"\nCheck PROMS and this tool's log before retrying.");
                }
            }
        }.execute();
    }

    /** Allows lifecycle tests to exercise the real worker and timer without credentials, networking or portal actions. */
    static class WorkServices {
        void verifyAccount(Path home) throws IOException {
            java.util.Optional<SharedCredentials.Credentials> saved=SharedCredentials.load(home);
            if(!saved.isPresent())throw new IOException("Set up your shared account before starting.");
            saved.get().close();
        }
        Path ensureDriver(Path home,java.util.function.Consumer<String> progress) throws Exception {
            return SharedChromeDriver.ensure(home,progress);
        }
        void run(ModuleSpec module) throws Exception {ModuleBootstrap.invoke(module);}
    }
}
