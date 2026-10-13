package trueopstoolkit;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** The single account editor used by the launcher and every module. */
public final class SharedCredentialsDialog {
    private SharedCredentialsDialog() { }
    public static boolean showDialog(Component parent,Path home) {
        final JDialog dialog=new JDialog(parent instanceof Window?(Window)parent:SwingUtilities.getWindowAncestor(parent),"Shared account | TrueOps Toolkit",Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body=new JPanel(new BorderLayout(0,16));body.setBorder(new EmptyBorder(20,22,20,22));
        body.add(Ui.note("This account is shared by all TUAR and PROMS tools.\nWindows encrypts it for your user account. Login.txt does not store credentials."),BorderLayout.NORTH);
        JTextField username=new JTextField(28);JPasswordField password=new JPasswordField(28);
        JLabel passwordNote=Ui.label("Enter your password",12,Ui.MUTED,false);JCheckBox show=new JCheckBox("Show password");char echo=password.getEchoChar();
        show.addActionListener(e -> password.setEchoChar(show.isSelected()?(char)0:echo));
        JPanel fields=new JPanel(new GridLayout(0,1,0,8));fields.add(new JLabel("Username"));fields.add(username);fields.add(new JLabel("Password"));fields.add(password);fields.add(passwordNote);fields.add(show);body.add(fields,BorderLayout.CENTER);
        final char[][] previous={null};final boolean[] changed={false};
        try {
            Optional<SharedCredentials.Credentials> saved=SharedCredentials.load(home);
            if(saved.isPresent())try(SharedCredentials.Credentials credentials=saved.get()){username.setText(credentials.getUsername());previous[0]=credentials.copyPassword();passwordNote.setText("Leave the password blank to keep the saved password.");}
        } catch(IOException ex){passwordNote.setText("Cannot read saved account. Remove it and save a new one.");}
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));JButton remove=new JButton("Remove saved account"),cancel=new JButton("Cancel"),save=Ui.button("Save shared account",Ui.NAVY);
        remove.setEnabled(java.nio.file.Files.exists(SharedCredentials.file(home)));
        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            char[] entered=password.getPassword();char[] selected=entered.length==0 && previous[0]!=null?Arrays.copyOf(previous[0],previous[0].length):entered;
            try {
                SharedCredentials.save(home,username.getText().trim(),selected);
                changed[0]=true;
                // The vault is committed first; an interrupted cleanup never loses the account.
                SharedCredentialsMigration.cleanLegacy(home,null);changed[0]=true;dialog.dispose();
            } catch(IOException ex){Ui.showError(dialog,ex);}
            finally {Arrays.fill(entered,'\0');Arrays.fill(selected,'\0');}
        });
        remove.addActionListener(e -> {
            if(JOptionPane.showConfirmDialog(dialog,"Remove the shared account from all tools?\nYou will need to set up an account before your next run.","Remove shared account",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            try {SharedCredentialsMigration.cleanLegacy(home,null);SharedCredentials.delete(home);changed[0]=true;dialog.dispose();}
            catch(IOException ex){Ui.showError(dialog,ex);}
        });
        buttons.add(remove);buttons.add(cancel);buttons.add(save);body.add(buttons,BorderLayout.SOUTH);dialog.setContentPane(body);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.getRootPane().setDefaultButton(save);dialog.pack();dialog.setMinimumSize(new Dimension(600,390));dialog.setSize(650,400);dialog.setLocationRelativeTo(parent);
        try {dialog.setVisible(true);return changed[0];}
        finally {password.setText("");if(previous[0]!=null)Arrays.fill(previous[0],'\0');}
    }
}
