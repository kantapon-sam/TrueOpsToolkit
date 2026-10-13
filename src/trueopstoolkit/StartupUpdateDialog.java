package trueopstoolkit;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

/** Startup progress and a clear, optional handoff to the downloaded toolkit update. */
final class StartupUpdateDialog extends JDialog {
    private final JTextArea toolkit=Ui.note("Waiting to check GitHub Releases...");
    private final JTextArea driver=Ui.note("Waiting to check Google Chrome and the shared driver...");
    private final JLabel footer=Ui.label("Tools will be available when these checks finish.",12,Ui.MUTED,false);

    StartupUpdateDialog(JFrame owner,Runnable requestClose) {
        super(owner,"Checking for updates",false);
        JPanel body=new JPanel(new BorderLayout(0,16));body.setBorder(new EmptyBorder(20,24,20,24));
        body.add(Ui.label("Preparing TrueOps Toolkit",21,Ui.TEXT,true),BorderLayout.NORTH);
        JPanel checks=new JPanel(new GridLayout(0,1,0,6));
        checks.add(Ui.label("TrueOps Toolkit",14,Ui.TEXT,true));toolkit.setRows(2);checks.add(toolkit);
        checks.add(Ui.label("Shared ChromeDriver",14,Ui.TEXT,true));driver.setRows(2);checks.add(driver);
        body.add(checks,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(0,10));JProgressBar progress=new JProgressBar();progress.setIndeterminate(true);
        bottom.add(progress,BorderLayout.NORTH);bottom.add(footer,BorderLayout.SOUTH);body.add(bottom,BorderLayout.SOUTH);
        setContentPane(body);setSize(650,345);setMinimumSize(new Dimension(570,320));setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent event){requestClose.run();}});
    }

    void toolkitStatus(String message){toolkit.setText(message);}
    void driverStatus(String message){driver.setText(message);}
    void closing(){footer.setText("Toolkit will close after these checks finish safely.");}

    static boolean offerUpdate(JFrame owner,String version,boolean canInstallNow) {
        final boolean[] install={false};
        JDialog dialog=new JDialog(owner,"TrueOps Toolkit update available",Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body=new JPanel(new BorderLayout(0,16));body.setBorder(new EmptyBorder(20,24,20,24));
        body.add(Ui.label("Version "+version+" is ready",21,Ui.TEXT,true),BorderLayout.NORTH);
        JTextArea message=Ui.note("Update now closes Toolkit and installs the downloaded version.\n"
                +"Open TrueOpsToolkit.jar again after installation to use the new version.\n\n"
                +"Later keeps this session open and installs the update when you close Toolkit.\n"
                +"Your account, settings, and work files will be kept."
                +(canInstallNow?"":"\n\nClose the active tool before choosing Update now."));
        message.setRows(canInstallNow?6:8);body.add(message,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,10,0));
        JButton later=new JButton("Later"),now=Ui.button("Update now",Ui.NAVY);now.setEnabled(canInstallNow);
        later.addActionListener(event -> dialog.dispose());
        now.addActionListener(event -> {install[0]=true;dialog.dispose();});
        actions.add(later);actions.add(now);body.add(actions,BorderLayout.SOUTH);
        dialog.setContentPane(body);dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);dialog.getRootPane().setDefaultButton(later);
        dialog.setSize(650,canInstallNow?330:370);dialog.setMinimumSize(new Dimension(590,canInstallNow?330:370));dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return install[0];
    }

    static void showIssues(Component owner,String toolkitIssue,String driverIssue) {
        StringBuilder message=new StringBuilder("You can keep using Toolkit. Some automatic checks could not finish.\n");
        if(toolkitIssue!=null)message.append("\nToolkit: ").append(toolkitIssue)
                .append("\nCheck your internet/proxy connection, then use Check for updates.\n");
        if(driverIssue!=null)message.append("\nChromeDriver: ").append(driverIssue)
                .append("\nEnsure Chrome is installed, close active tools, and check your connection.\n")
                .append("Use Update ChromeDriver to retry; it is also checked before browser tasks.\n");
        javax.swing.JOptionPane.showMessageDialog(owner,message.toString(),"Startup update checks",javax.swing.JOptionPane.WARNING_MESSAGE);
    }
}
