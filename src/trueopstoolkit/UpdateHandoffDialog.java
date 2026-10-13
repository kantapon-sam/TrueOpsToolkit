package trueopstoolkit;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.border.EmptyBorder;

/** Visible while the launcher prepares its verified installer off the UI thread. */
final class UpdateHandoffDialog extends JDialog {
    UpdateHandoffDialog(JFrame owner,String version) {
        super(owner,"Updating TrueOps Toolkit",false);
        JPanel body=new JPanel(new BorderLayout(0,18));body.setBorder(new EmptyBorder(24,26,24,26));
        body.add(Ui.label("Installing version "+version,21,Ui.TEXT,true),BorderLayout.NORTH);
        body.add(Ui.note("Preparing the installer and verifying the downloaded update...\nYour account, settings, and work files will be kept."),BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(0,12));JProgressBar progress=new JProgressBar();progress.setIndeterminate(true);
        bottom.add(progress,BorderLayout.NORTH);bottom.add(Ui.label("Toolkit will reopen automatically after the update.",13,Ui.MUTED,false),BorderLayout.SOUTH);
        body.add(bottom,BorderLayout.SOUTH);setContentPane(body);setSize(610,245);setMinimumSize(new Dimension(560,245));
        setLocationRelativeTo(owner);setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    }
}
