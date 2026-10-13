package trueopstoolkit;

import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A masked smoke image must not mutate any live editor document. */
public final class UiSnapshotTest {
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("trueops-ui-snapshot-");
        Path image=directory.resolve("masked.png");
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame=new JFrame("Synthetic snapshot test");
                try {
                    JTextField username=new JTextField("synthetic-user");
                    JPasswordField password=new JPasswordField("synthetic-password");
                    password.setEchoChar((char)0);
                    AtomicInteger events=new AtomicInteger();
                    DocumentListener listener=new DocumentListener(){
                        public void insertUpdate(DocumentEvent e){events.incrementAndGet();}
                        public void removeUpdate(DocumentEvent e){events.incrementAndGet();}
                        public void changedUpdate(DocumentEvent e){events.incrementAndGet();}
                    };
                    username.getDocument().addDocumentListener(listener);
                    password.getDocument().addDocumentListener(listener);
                    JPanel panel=new JPanel(new GridLayout(2,1));
                    panel.add(username);panel.add(password);frame.setContentPane(panel);
                    frame.setSize(400,150);frame.setVisible(true);
                    Ui.snapshot(frame,image,true);
                    if(events.get()!=0)throw new AssertionError("Snapshot changed an editor document.");
                    if(!"synthetic-user".equals(username.getText()))throw new AssertionError("Username changed.");
                    char[] value=password.getPassword();
                    try {
                        if(!Arrays.equals(value,"synthetic-password".toCharArray()))throw new AssertionError("Password changed.");
                    } finally {Arrays.fill(value,'\0');}
                    java.awt.image.BufferedImage rendered=javax.imageio.ImageIO.read(image.toFile());
                    for(JTextField field:new JTextField[]{username,password}) {
                        java.awt.Point position=SwingUtilities.convertPoint(field,0,0,panel);
                        java.awt.Insets insets=field.getInsets();
                        int background=field.getBackground().getRGB();
                        for(int y=insets.top+2;y<field.getHeight()-insets.bottom-2;y++) {
                            for(int x=insets.left+2;x<field.getWidth()-insets.right-2;x++) {
                                if(rendered.getRGB(position.x+x,position.y+y)!=background)
                                    throw new AssertionError("Snapshot field content was not fully masked.");
                            }
                        }
                    }
                } catch(Exception ex) {throw new RuntimeException(ex);}
                finally {frame.dispose();}
            });
            System.out.println("PASS: snapshot masks field pixels without changing documents or dirty state.");
        } finally {Files.deleteIfExists(image);Files.deleteIfExists(directory);}
    }
}
