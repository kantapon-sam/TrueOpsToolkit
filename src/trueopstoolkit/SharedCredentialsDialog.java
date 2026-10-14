package trueopstoolkit;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;

/** The single account editor used by the launcher and every module. */
public final class SharedCredentialsDialog {
    private SharedCredentialsDialog() { }
    public static boolean showDialog(Component parent,Path home) {
        Window owner=parent instanceof Window?(Window)parent:parent==null?null:SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog=new JDialog(owner,"Shared account | TrueOps Toolkit",Dialog.ModalityType.APPLICATION_MODAL);
        JPanel root=new JPanel(new BorderLayout());root.setBackground(Ui.BG);
        root.add(Ui.header("Shared account","One account for all four PROMS tools"),BorderLayout.NORTH);
        JPanel body=new JPanel(new BorderLayout(0,20));body.setOpaque(false);body.setBorder(new EmptyBorder(20,24,22,24));
        JTextField username=new JTextField(28);JPasswordField password=new JPasswordField(28);
        username.setName("sharedUsername");password.setName("sharedPassword");
        for(JTextField field:new JTextField[]{username,password}) {
            field.setFont(new Font("Tahoma",Font.PLAIN,16));field.setForeground(Ui.TEXT);
            field.setBorder(new CompoundBorder(field.getBorder(),new EmptyBorder(6,8,6,8)));
        }
        final char echo='\u2022';password.setEchoChar(echo);
        JTextArea passwordNote=Ui.note("Enter your password. Use Show password to check what you typed.");
        passwordNote.setFont(new Font("Tahoma",Font.PLAIN,12));passwordNote.setRows(2);
        JCheckBox show=new JCheckBox("Show password");show.setName("showPassword");show.setOpaque(false);show.setForeground(Ui.TEXT);show.setFont(new Font("Tahoma",Font.PLAIN,12));
        show.addActionListener(e -> password.setEchoChar(show.isSelected()?(char)0:echo));
        JLabel accountState=Ui.label("No account saved",12,Ui.MUTED,true);
        JPanel fields=new JPanel(new GridBagLayout());fields.setBackground(Color.WHITE);
        fields.setBorder(new CompoundBorder(new LineBorder(new Color(217,226,237)),new EmptyBorder(16,18,16,18)));
        addField(fields,0,accountState,0);addField(fields,1,Ui.label("Username",14,Ui.TEXT,true),16);addField(fields,2,username,6);
        JPanel passwordHeading=new JPanel(new BorderLayout());passwordHeading.setOpaque(false);
        passwordHeading.add(Ui.label("Password",14,Ui.TEXT,true),BorderLayout.WEST);passwordHeading.add(show,BorderLayout.EAST);
        addField(fields,3,passwordHeading,16);addField(fields,4,password,6);addField(fields,5,passwordNote,8);
        body.add(fields,BorderLayout.CENTER);
        final boolean[] changed={false};
        try {
            Optional<SharedCredentials.Credentials> saved=SharedCredentials.load(home);
            if(saved.isPresent())try(SharedCredentials.Credentials credentials=saved.get()) {
                username.setText(credentials.getUsername());char[] value=credentials.copyPassword();
                try {password.setText(new String(value));} finally {Arrays.fill(value,'\0');}
                accountState.setText("Saved account");accountState.setForeground(new Color(26,125,96));
                passwordNote.setText("Your saved password is loaded. Show it to review, or replace it to update.");
            }
        } catch(IOException ex){accountState.setText("Account unavailable");passwordNote.setText("Cannot read the saved account. Remove it and save a new one.");}
        JPanel footer=new JPanel(new BorderLayout(0,14));footer.setOpaque(false);
        JTextArea storageNote=Ui.note("Saved without encryption in config/credentials.properties.\nAnyone who can read this file can see your username and password.");
        storageNote.setName("accountStorageNote");footer.add(storageNote,BorderLayout.NORTH);
        JPanel buttons=new JPanel(new BorderLayout(12,0));buttons.setOpaque(false);
        JPanel primary=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));primary.setOpaque(false);
        JButton remove=new JButton("Remove saved account"),cancel=new JButton("Cancel"),save=Ui.button("Save shared account",Ui.NAVY);
        remove.setEnabled(java.nio.file.Files.exists(SharedCredentials.file(home)) || java.nio.file.Files.exists(SharedCredentials.encryptedFile(home)));
        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            char[] entered=password.getPassword();
            try {
                SharedCredentials.save(home,username.getText().trim(),entered);
                changed[0]=true;
                // The shared account is committed first; cleanup cannot lose the saved account.
                SharedCredentialsMigration.cleanLegacy(home,null);changed[0]=true;dialog.dispose();
            } catch(IOException ex){Ui.showError(dialog,ex);}
            finally {Arrays.fill(entered,'\0');}
        });
        remove.addActionListener(e -> {
            if(JOptionPane.showConfirmDialog(dialog,"Remove the shared account from all tools?\nYou will need to set up an account before your next run.","Remove shared account",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            try {SharedCredentialsMigration.cleanLegacy(home,null);SharedCredentials.delete(home);changed[0]=true;dialog.dispose();}
            catch(IOException ex){Ui.showError(dialog,ex);}
        });
        JPanel secondary=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));secondary.setOpaque(false);secondary.add(remove);
        buttons.add(secondary,BorderLayout.WEST);primary.add(cancel);primary.add(save);buttons.add(primary,BorderLayout.EAST);
        footer.add(buttons,BorderLayout.SOUTH);body.add(footer,BorderLayout.SOUTH);root.add(body,BorderLayout.CENTER);dialog.setContentPane(root);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.getRootPane().setDefaultButton(save);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();dialog.setMinimumSize(new Dimension(680,540));dialog.setSize(Math.max(680,dialog.getWidth()),Math.max(540,dialog.getHeight()));dialog.setLocationRelativeTo(parent);
        try {dialog.setVisible(true);return changed[0];}
        finally {password.setEchoChar(echo);password.setText("");}
    }

    private static void addField(JPanel panel,int row,Component component,int top) {
        GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=row;c.weightx=1;
        c.fill=GridBagConstraints.HORIZONTAL;c.anchor=GridBagConstraints.NORTHWEST;c.insets=new Insets(top,0,0,0);
        panel.add(component,c);
    }
}
