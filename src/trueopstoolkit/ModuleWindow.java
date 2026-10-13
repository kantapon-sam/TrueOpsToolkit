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
    private final JTextArea status=Ui.note("");
    private final JTextField username=new JTextField(26),search=new JTextField(26),description=new JTextField(26);
    private final JButton sharedAccount=new JButton("Shared account settings");
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JPanel editor=new JPanel(new BorderLayout());
    private final JButton start,check,save,reload;
    private boolean running,dirty,loading,loaded;

    ModuleWindow(Path home,ModuleSpec module) {
        super(module.title+" | TrueOps Toolkit v"+AppVersion.current());this.home=home;this.module=module;settings=new ModuleSettings(home,module);
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
        if(module==ModuleSpec.APPROVE) {
            addRow(form,row++,"Search",search);addRow(form,row++,"Description",description);
            addRow(form,row++,"",Ui.note("Search: separate two conditions with && to require both matches.\nDescription: the note included with each approval."));
        }
        addRow(form,row++,"",Ui.note("Your shared username and password are encrypted by Windows.\nWhen the tool starts, review the Chrome window and click Sign in."));
        GridBagConstraints spacer=new GridBagConstraints();spacer.gridy=row;spacer.weighty=1;form.add(new JPanel(),spacer);
        tabs.addTab("Login / Settings",new JScrollPane(form));
        String[] columns=module==ModuleSpec.REF?new String[]{"Ref No.","Type","Description"}:new String[]{"Ref No.","Deletion reason"};
        tableModel=new DefaultTableModel(columns,0);table=new JTable(tableModel);table.setRowHeight(28);table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost",Boolean.TRUE);
        if(settings.hasReferences()) {
            JPanel references=new JPanel(new BorderLayout(0,8));references.setBorder(new EmptyBorder(10,10,10,10));
            references.add(Ui.note("Edit cells directly • Changes are saved to this tool's Ref_No.csv.\n"+(module==ModuleSpec.REF?"Ref No. and Type are required in every row. Description is optional.":"Ref No. and a deletion reason are required in every row.")),BorderLayout.NORTH);
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
        tableModel.addTableModelListener(e -> changed());loadSettings();
        addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){
            if(running && JOptionPane.showConfirmDialog(ModuleWindow.this,"A task is running. Stop it and close this window?","Stop tool",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            if(!running && !discardEdits())return;
            dispose();System.exit(0);
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
            settings.load();refreshAccount();search.setText(settings.search);description.setText(settings.description);
            tableModel.setRowCount(0);for(String[] row:settings.rows)tableModel.addRow(row);
            loaded=true;clean();status.setText("Ready to edit • Save settings to keep your changes.\nStart saves the current settings and runs "+module.title+".");
        } catch(Exception ex) {loaded=false;status.setText("Could not load settings: "+ex.getMessage()+"\nCheck the files in the data folder, then click Reload saved settings.");}
        finally {loading=false;busy(false);}
    }
    private void finishEditing(){if(table.isEditing())table.getCellEditor().stopCellEditing();}
    private void collect() throws IOException {
        finishEditing();
        settings.search=search.getText().trim();settings.description=description.getText();settings.rows.clear();
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
        try {java.util.Optional<SharedCredentials.Credentials> saved=SharedCredentials.load(home);
            if(!saved.isPresent())throw new IOException("Set up your shared account before starting.");
            saved.get().close();
        } catch(IOException ex){Ui.showError(this,ex);return;}
        if(module==ModuleSpec.DELETE && JOptionPane.showConfirmDialog(this,"Delete the "+settings.rows.size()+" sites listed in this table?\nReview every reference and deletion reason before confirming.","Start Delete Site",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
        if(!saveSettings(true))return;
        busy(true);status.setText("Checking shared ChromeDriver before starting...");
        new SwingWorker<Void,Void>(){
            @Override protected Void doInBackground() throws Exception {
                Path driver=SharedChromeDriver.ensure(home,ModuleWindow.this::updateStatus);
                System.setProperty("webdriver.chrome.driver",driver.toString());
                updateStatus("Running • Sign in through the Chrome window.\nClick Latest log in the launcher for more details.");
                ModuleBootstrap.invoke(module);return null;
            }
            @Override protected void done(){busy(false);try{get();status.setText("Process finished. Check the results in PROMS and the log.\nYou can edit the settings before the next run.");}catch(Exception ex){status.setText("The tool stopped with an error: "+ex.getCause()+"\nSee this tool's log for details.");}}
        }.execute();
    }
}
