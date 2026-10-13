package trueopstoolkit;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Real dialog and Windows vault, using isolated synthetic credentials only. */
public final class SharedCredentialsDialogTest {
    private static int checks;
    private static final String DEMO_PASSWORD="Demo-only-123!";

    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("trueops-account-dialog-");
        SwingUtilities.invokeAndWait(Ui::setup);
        try {
            revealAndCancel(home);
            editAndSave(home);
            blankDoesNotReplaceSavedPassword(home);
            emptyAccount(home.resolve("new-account"));
            unreadableAccount(home.resolve("unreadable-account"));
            System.out.println("PASS: shared account dialog ("+checks+" checks); only synthetic credentials used.");
        } finally {
            onEdt(()->{for(Window window:Window.getWindows())window.dispose();});
            ToolkitUpdater.deleteTree(home);
        }
    }

    private static void revealAndCancel(Path home) throws Exception {
        SharedCredentials.save(home,"demo.operator",DEMO_PASSWORD.toCharArray());
        byte[] original=Files.readAllBytes(SharedCredentials.file(home));
        Session session=open(home);
        JPasswordField field=password(session.dialog);
        onEdt(()->{
            check(Arrays.equals(DEMO_PASSWORD.toCharArray(),field.getPassword()),"Saved password is actually loaded, not placeholder dots");
            check(field.getEchoChar()=='\u2022',"Saved password is masked by default");
            check(!show(session.dialog).isSelected(),"Show password defaults off");
            snapshot(session.dialog,"shared-account-masked.png");
            show(session.dialog).doClick();
            check(field.getEchoChar()==0,"Show password reveals the saved value");
            check(Arrays.equals(DEMO_PASSWORD.toCharArray(),field.getPassword()),"Reveal leaves the actual value unchanged");
            snapshot(session.dialog,"shared-account-visible-demo.png");
            show(session.dialog).doClick();
            check(field.getEchoChar()=='\u2022',"Turning Show off restores dots");
            show(session.dialog).doClick();
            field.setText("Synthetic-unsaved-change");
            button(session.dialog,"Cancel").doClick();
        });
        check(!session.finish(),"Cancel reports no account change");
        check(Arrays.equals(original,Files.readAllBytes(SharedCredentials.file(home))),"Reveal and Cancel do not rewrite saved credentials");
        check(field.getPassword().length==0 && field.getEchoChar()=='\u2022',"Close clears and remasks the editor");
        Session reopened=open(home);
        onEdt(()->{
            check(password(reopened.dialog).getEchoChar()=='\u2022',"Reopening never retains revealed state");
            check(Arrays.equals(DEMO_PASSWORD.toCharArray(),password(reopened.dialog).getPassword()),"Cancelled edit does not change saved password");
            reopened.dialog.dispatchEvent(new WindowEvent(reopened.dialog,WindowEvent.WINDOW_CLOSING));
        });
        check(!reopened.finish(),"Window close cancels without saving");
    }

    private static void editAndSave(Path home) throws Exception {
        Session usernameOnly=open(home);
        onEdt(()->{
            username(usernameOnly.dialog).setText("demo.updated");
            button(usernameOnly.dialog,"Save shared account").doClick();
        });
        check(usernameOnly.finish(),"Username edit saves");
        try(SharedCredentials.Credentials account=SharedCredentials.load(home).get()) {
            check("demo.updated".equals(account.getUsername()),"Username edit persisted");
            check(Arrays.equals(DEMO_PASSWORD.toCharArray(),account.copyPassword()),"Unchanged loaded password is preserved when saving");
        }
        Session replacement=open(home);
        onEdt(()->{
            show(replacement.dialog).doClick();
            password(replacement.dialog).setText("Synthetic-new-password!");
            button(replacement.dialog,"Save shared account").doClick();
        });
        check(replacement.finish(),"Password replacement saves");
        try(SharedCredentials.Credentials account=SharedCredentials.load(home).get()) {
            check(Arrays.equals("Synthetic-new-password!".toCharArray(),account.copyPassword()),"Replacement password persisted");
        }
        String stored=new String(Files.readAllBytes(SharedCredentials.file(home)),StandardCharsets.UTF_8);
        check(!stored.contains("Synthetic-new-password!")&&!stored.contains("demo.updated"),"Saved account remains encrypted");
    }

    private static void blankDoesNotReplaceSavedPassword(Path home) throws Exception {
        byte[] original=Files.readAllBytes(SharedCredentials.file(home));
        Session session=open(home);
        onEdt(()->password(session.dialog).setText(""));
        SwingUtilities.invokeLater(()->button(session.dialog,"Save shared account").doClick());
        JDialog error=awaitDialog("TrueOps Toolkit");
        onEdt(()->{
            check(session.dialog.isShowing(),"Empty password keeps the editor available");
            error.dispose();
            button(session.dialog,"Cancel").doClick();
        });
        check(!session.finish(),"Empty password never reports a successful save");
        check(Arrays.equals(original,Files.readAllBytes(SharedCredentials.file(home))),"Empty password cannot overwrite saved account");
    }

    private static void emptyAccount(Path home) throws Exception {
        Session session=open(home);
        onEdt(()->{
            check(username(session.dialog).getText().isEmpty(),"New account has an empty username");
            check(password(session.dialog).getPassword().length==0,"New account has no fake password placeholder");
            check(!button(session.dialog,"Remove saved account").isEnabled(),"Remove is disabled when nothing is saved");
            username(session.dialog).setText("demo.new");
            password(session.dialog).setText(DEMO_PASSWORD);
            show(session.dialog).doClick();
            check(password(session.dialog).getEchoChar()==0,"Show also reveals newly typed passwords");
            button(session.dialog,"Save shared account").doClick();
        });
        check(session.finish(),"New account saves through the same dialog");
        try(SharedCredentials.Credentials account=SharedCredentials.load(home).get()) {
            check(Arrays.equals(DEMO_PASSWORD.toCharArray(),account.copyPassword()),"New account can be decrypted from the vault");
        }
    }

    private static void unreadableAccount(Path home) throws Exception {
        Files.createDirectories(SharedCredentials.file(home).getParent());
        byte[] corrupt="synthetic-invalid-vault".getBytes(StandardCharsets.UTF_8);
        Files.write(SharedCredentials.file(home),corrupt);
        Session session=open(home);
        onEdt(()->{
            show(session.dialog).doClick();
            check(password(session.dialog).getPassword().length==0,"Unreadable vault cannot reveal a stale password from another dialog");
            button(session.dialog,"Cancel").doClick();
        });
        check(!session.finish(),"Unreadable account remains unchanged on Cancel");
        check(Arrays.equals(corrupt,Files.readAllBytes(SharedCredentials.file(home))),"Read error preserves existing vault bytes");
    }

    private static Session open(Path home) throws Exception {
        Session session=new Session();
        SwingUtilities.invokeLater(()->{
            try {session.saved=SharedCredentialsDialog.showDialog(new JPanel(),home);}
            catch(Throwable failure){session.failure.set(failure);}
            finally {session.finished.countDown();}
        });
        session.dialog=awaitDialog("Shared account | TrueOps Toolkit");return session;
    }

    private static JDialog awaitDialog(String title) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            AtomicReference<JDialog> found=new AtomicReference<>();
            onEdt(()->{for(Window window:Window.getWindows())if(window instanceof JDialog&&window.isShowing()&&title.equals(((JDialog)window).getTitle()))found.set((JDialog)window);});
            if(found.get()!=null)return found.get();Thread.sleep(30);
        }
        throw new AssertionError("Expected visible dialog: "+title);
    }

    private static JPasswordField password(JDialog dialog){return (JPasswordField)named(dialog,"sharedPassword");}
    private static JTextField username(JDialog dialog){return (JTextField)named(dialog,"sharedUsername");}
    private static JCheckBox show(JDialog dialog){return (JCheckBox)named(dialog,"showPassword");}
    private static Component named(JDialog dialog,String name){for(Component c:components(dialog.getContentPane()))if(name.equals(c.getName()))return c;throw new AssertionError("Missing control: "+name);}
    private static JButton button(JDialog dialog,String label){for(Component c:components(dialog.getContentPane()))if(c instanceof JButton&&label.equals(((JButton)c).getText()))return (JButton)c;throw new AssertionError("Missing button: "+label);}
    private static List<Component> components(Container parent){List<Component> result=new ArrayList<>();for(Component c:parent.getComponents()){result.add(c);if(c instanceof Container)result.addAll(components((Container)c));}return result;}
    private static void onEdt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}

    /** Screenshots contain only this test's synthetic demo account, never a personal vault. */
    private static void snapshot(JDialog dialog,String name) {
        try {
            Container content=dialog.getContentPane();
            BufferedImage image=new BufferedImage(content.getWidth(),content.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics=image.createGraphics();try{content.printAll(graphics);}finally{graphics.dispose();}
            Path output=Paths.get("build/test-reports/account-dialog",name);Files.createDirectories(output.getParent());ImageIO.write(image,"png",output.toFile());
        }catch(Exception failure){throw new AssertionError("Synthetic dialog rendering failed",failure);}
    }

    private static final class Session {
        final CountDownLatch finished=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        volatile boolean saved;JDialog dialog;
        boolean finish()throws Exception {
            if(!finished.await(20,TimeUnit.SECONDS))throw new AssertionError("Account dialog did not finish");
            if(failure.get()!=null)throw new AssertionError("Account dialog failed",failure.get());return saved;
        }
    }
}
