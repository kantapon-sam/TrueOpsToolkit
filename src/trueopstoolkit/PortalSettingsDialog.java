package trueopstoolkit;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Shared portal selector, available from the launcher and every tool. */
public final class PortalSettingsDialog {
    private PortalSettingsDialog() { }
    public static boolean showDialog(Component parent,Path home) {
        Window owner=parent instanceof Window?(Window)parent:SwingUtilities.getWindowAncestor(parent);
        JDialog dialog=new JDialog(owner,"Portal settings | TrueOps Toolkit",Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body=new JPanel(new BorderLayout(0,14));body.setBorder(new EmptyBorder(20,22,20,22));
        body.add(Ui.note("All tools use this portal and your shared account.\nThe selected mode is applied when a tool starts."),BorderLayout.NORTH);
        JComboBox<PortalSettings.Mode> modes=new JComboBox<>(PortalSettings.Mode.values());
        JTextArea detail=Ui.note("");detail.setRows(4);
        JPanel fields=new JPanel(new BorderLayout(0,12));fields.add(modes,BorderLayout.NORTH);fields.add(detail,BorderLayout.CENTER);body.add(fields,BorderLayout.CENTER);
        String[] loadNote={""};Runnable refresh=()->{PortalSettings.Mode mode=(PortalSettings.Mode)modes.getSelectedItem();detail.setText(loadNote[0]+mode.getLoginUrl()+"\n"+(mode==PortalSettings.Mode.INTERNAL?"Internal portal • Connect to the company network or VPN.":"External portal • Complete CAPTCHA when requested."));};
        try{modes.setSelectedItem(PortalSettings.load(home));}catch(IOException error){loadNote[0]="Saved mode could not be loaded. Choose a mode and save to repair it.\n";}
        modes.addActionListener(event->refresh.run());refresh.run();
        boolean[] saved={false};JButton cancel=new JButton("Cancel"),save=Ui.button("Save portal mode",Ui.NAVY);
        cancel.addActionListener(event->dialog.dispose());save.addActionListener(event->{try{PortalSettings.save(home,(PortalSettings.Mode)modes.getSelectedItem());saved[0]=true;dialog.dispose();}catch(IOException error){Ui.showError(dialog,error);}});
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(cancel);buttons.add(save);body.add(buttons,BorderLayout.SOUTH);
        dialog.setContentPane(body);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.getRootPane().setDefaultButton(save);dialog.pack();dialog.setSize(650,310);dialog.setLocationRelativeTo(parent);dialog.setVisible(true);return saved[0];
    }
}
