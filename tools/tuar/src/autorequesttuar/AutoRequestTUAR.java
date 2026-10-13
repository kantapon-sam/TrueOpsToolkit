package autorequesttuar;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Desktop assistant for submitting TUAR New Requests from requests.xlsx.
 */
public class AutoRequestTUAR extends JFrame {

    private static final String APP_VERSION = "1.0.0";
    private static final String AUTORUN_PROPERTY =
            "autorequesttuar.autorun";

    private final JTextField serverIpField = new JTextField(24);
    private final JTextField usernameField = new JTextField(24);
    private final JPasswordField passwordField = new JPasswordField(24);
    private final JComboBox<String> loginTypeBox =
            new JComboBox<>(new String[]{"HR", "CLLS"});
    private final JCheckBox saveLoginBox = new JCheckBox(
            "Remember encrypted password on this computer", true);
    private final JCheckBox showPasswordBox = new JCheckBox("Show password");
    private final JTextField workbookField = new JTextField(32);
    private final JTextField attachmentsField = new JTextField(32);
    private final JCheckBox headlessBox = new JCheckBox("Run Chrome in the background");
    private final JButton browseWorkbookButton = new JButton("Browse...");
    private final JButton browseAttachmentsButton = new JButton("Browse...");
    private final JButton saveSettingsButton = new JButton("Save settings");
    private final JButton forgetLoginButton = new JButton("Forget saved password");
    private final JButton sharedAccountButton = new JButton("Shared account settings...");
    private final boolean managedAccount = SharedAccount.isManaged();
    private final char passwordEchoChar = passwordField.getEchoChar();

    private final JButton loginButton =
            new JButton("Run selected workbook");
    private final JTextArea statusArea = new JTextArea(7, 58);

    private final TuarBrowser tuarBrowser = new TuarBrowser();
    private final CredentialStore credentialStore = new CredentialStore();
    private final SettingsStore settingsStore = new SettingsStore();

    public AutoRequestTUAR() {
        super(displayedTitle());
        initialiseDefaults();
        buildUi();
        bindActions();
        loadSavedCredentials();
        loadSavedSettings();
        initialiseAttachmentsFolder();
    }

    private void initialiseDefaults() {
        workbookField.setText(AppPaths.getAppFolder().resolve("requests.xlsx")
                .toAbsolutePath().normalize().toString());
        attachmentsField.setText(AppPaths.getAttachmentsFolder().toString());
        headlessBox.setSelected(Boolean.getBoolean("autorequesttuar.browser.headless"));
        String serverIp = System.getenv("TUAR_IP");
        serverIpField.setText(
                serverIp == null || serverIp.trim().isEmpty()
                        ? TuarServer.DEFAULT_IP : serverIp.trim());
        if (!managedAccount) {
            String usernameFromEnvironment = System.getenv("TUAR_USER");
            if (usernameFromEnvironment != null
                    && !usernameFromEnvironment.trim().isEmpty()) {
                usernameField.setText(usernameFromEnvironment.trim());
            }
            String passwordFromEnvironment = System.getenv("TUAR_PASSWORD");
            if (passwordFromEnvironment != null) {
                passwordField.setText(passwordFromEnvironment);
            }
        } else {
            usernameField.setEditable(false);
            passwordField.setEditable(false);
        }
        String loginType = System.getenv("TUAR_LOGIN_TYPE");
        if ("CLLS".equalsIgnoreCase(loginType)) {
            loginTypeBox.setSelectedItem("CLLS");
        }

        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setBackground(new Color(248, 248, 248));
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JLabel title = new JLabel(displayedTitle());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        content.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        addSection(form, gbc, row++, "Login");
        addRow(form, gbc, row++, "TUAR IP *", serverIpField);
        addRow(form, gbc, row++, "Username *", usernameField);
        addRow(form, gbc, row++, "Password *", passwordField);
        if (!managedAccount) {
            addRow(form, gbc, row++, "", showPasswordBox);
        }
        addRow(form, gbc, row++, "Login Type *", loginTypeBox);
        if (managedAccount) {
            addRow(form, gbc, row++, "Toolkit account", sharedAccountButton);
        } else {
            addRow(form, gbc, row++, "Save Login", saveLoginBox);
        }
        addSection(form, gbc, row++, "Files and browser");
        addRow(form, gbc, row++, "Requests workbook *",
                withBrowseButton(workbookField, browseWorkbookButton));
        addRow(form, gbc, row++, "Attachments folder *",
                withBrowseButton(attachmentsField, browseAttachmentsButton));
        addRow(form, gbc, row++, "Chrome", headlessBox);
        addRow(form, gbc, row++, "ChromeDriver", new JLabel(
                "Automatically updated to match Chrome before starting the browser"));

        JPanel settingsActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        settingsActions.add(saveSettingsButton);
        if (!managedAccount) {
            settingsActions.add(forgetLoginButton);
        }
        addRow(form, gbc, row++, "", settingsActions);
        addRow(form, gbc, row++, "", new JLabel(
                "Save settings does not log in or submit requests."));

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1;
        form.add(loginButton, gbc);

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        formScroll.getVerticalScrollBar().setUnitIncrement(16);
        content.add(formScroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(5, 5));
        JLabel note = new JLabel(
                "Run updates Excel dropdowns from TUAR, reads "
                + "the selected workbook, and automatically submits enabled rows.");
        note.setForeground(new Color(130, 60, 0));
        bottom.add(note, BorderLayout.NORTH);
        bottom.add(new JScrollPane(statusArea), BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);

        setContentPane(content);
        setMinimumSize(new Dimension(760, 550));
        setSize(900, 760);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

    private void bindActions() {
        final Thread browserCleanupHook = new Thread(
                tuarBrowser::shutdown, "tuar-browser-cleanup");
        Runtime.getRuntime().addShutdownHook(browserCleanupHook);
        loginButton.addActionListener(this::loginAndLoadOptions);
        saveSettingsButton.addActionListener(event -> saveSettings());
        forgetLoginButton.addActionListener(event -> forgetSavedPassword());
        sharedAccountButton.addActionListener(event -> editSharedAccount());
        browseWorkbookButton.addActionListener(event -> browseWorkbook());
        browseAttachmentsButton.addActionListener(event -> browseAttachments());
        showPasswordBox.addActionListener(event -> passwordField.setEchoChar(
                showPasswordBox.isSelected() ? (char) 0 : passwordEchoChar));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                tuarBrowser.shutdown();
                try {
                    Runtime.getRuntime().removeShutdownHook(browserCleanupHook);
                } catch (IllegalStateException ignored) {
                    // The JVM shutdown hook is already running.
                }
            }
        });
    }

    private void loadSavedSettings() {
        try {
            Properties values = settingsStore.load();
            serverIpField.setText(values.getProperty("serverIp", serverIpField.getText()));
            if (!managedAccount) {
                usernameField.setText(values.getProperty("username", usernameField.getText()));
            }
            String loginType = values.getProperty("loginType",
                    String.valueOf(loginTypeBox.getSelectedItem()));
            loginTypeBox.setSelectedItem("CLLS".equalsIgnoreCase(loginType) ? "CLLS" : "HR");
            workbookField.setText(values.getProperty("workbook", workbookField.getText()));
            attachmentsField.setText(values.getProperty("attachments", attachmentsField.getText()));
            headlessBox.setSelected(Boolean.parseBoolean(values.getProperty("headless",
                    Boolean.toString(headlessBox.isSelected()))));
            if (!managedAccount) {
                saveLoginBox.setSelected(Boolean.parseBoolean(values.getProperty("rememberLogin",
                        Boolean.toString(saveLoginBox.isSelected()))));
                if (!saveLoginBox.isSelected()) {
                    passwordField.setText("");
                }
            }
            if (!values.isEmpty()) {
                appendStatus("Saved settings loaded. You can edit and save them here.");
            }
        } catch (IOException | IllegalArgumentException ex) {
            appendStatus("Could not load settings: " + ex.getMessage());
        }
    }

    private Properties settingsFromFields() {
        Properties values = new Properties();
        values.setProperty("serverIp", TuarServer.normalizeIp(serverIpField.getText()));
        if (!managedAccount) {
            values.setProperty("username", usernameField.getText().trim());
            values.setProperty("rememberLogin", Boolean.toString(saveLoginBox.isSelected()));
        }
        values.setProperty("loginType", String.valueOf(loginTypeBox.getSelectedItem()));
        values.setProperty("workbook", requestsPath().toString());
        values.setProperty("attachments", attachmentsPath().toString());
        values.setProperty("headless", Boolean.toString(headlessBox.isSelected()));
        return values;
    }

    private void saveSettings() {
        char[] password = passwordField.getPassword();
        try {
            Properties values = settingsFromFields();
            if (!managedAccount && saveLoginBox.isSelected()
                    && (values.getProperty("username").isEmpty() || password.length == 0)) {
                showValidation("Enter a username and password, or turn off Remember encrypted password.");
                return;
            }
            if (!managedAccount) {
                if (saveLoginBox.isSelected()) {
                    credentialStore.save(values.getProperty("serverIp"),
                            values.getProperty("username"), password, values.getProperty("loginType"));
                } else {
                    credentialStore.delete();
                }
            }
            settingsStore.save(values);
            serverIpField.setText(values.getProperty("serverIp"));
            workbookField.setText(values.getProperty("workbook"));
            attachmentsField.setText(values.getProperty("attachments"));
            appendStatus("Settings saved. No login or request submission was performed.");
        } catch (IllegalArgumentException ex) {
            showValidation(ex.getMessage());
        } catch (IOException ex) {
            showError("Could not save all settings.", ex);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void forgetSavedPassword() {
        try {
            credentialStore.delete();
            passwordField.setText("");
            saveLoginBox.setSelected(false);
            Properties values = new Properties();
            values.setProperty("rememberLogin", "false");
            settingsStore.save(values);
            appendStatus("Saved login removed and the password field cleared.");
        } catch (IOException ex) {
            showError("Could not complete removing the saved login.", ex);
        }
    }

    private void browseWorkbook() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose the TUAR requests workbook");
        chooser.setFileFilter(new FileNameExtensionFilter("Excel workbook (*.xlsx)", "xlsx"));
        try {
            chooser.setSelectedFile(requestsPath().toFile());
        } catch (IllegalArgumentException ignored) {
            // A manually typed invalid path must not prevent choosing a valid file.
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            workbookField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void browseAttachments() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose the TUAR attachments folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        try {
            chooser.setSelectedFile(attachmentsPath().toFile());
        } catch (IllegalArgumentException ignored) {
            // Let the user replace an invalid path.
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            attachmentsField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void loadSavedCredentials() {
        if (managedAccount) {
            try {
                if (reloadSharedAccount()) {
                    appendStatus("Shared encrypted Toolkit account loaded.");
                } else {
                    appendStatus("Set the shared Toolkit account before running.");
                }
            } catch (IOException ex) {
                appendStatus(ex.getMessage());
            }
            return;
        }
        try {
            Optional<CredentialStore.SavedCredentials> saved =
                    credentialStore.load();
            if (!saved.isPresent()) {
                appendStatus("No saved login was found.");
                return;
            }
            try (CredentialStore.SavedCredentials credentials = saved.get()) {
                char[] password = credentials.copyPassword();
                try {
                    serverIpField.setText(credentials.getServerIp());
                    usernameField.setText(credentials.getUsername());
                    passwordField.setText(String.valueOf(password));
                    loginTypeBox.setSelectedItem(credentials.getLoginType());
                    saveLoginBox.setSelected(true);
                    appendStatus("Saved encrypted login loaded.");
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        } catch (IOException ex) {
            appendStatus(
                    "Could not load the saved login: " + ex.getMessage());
        }
    }

    private boolean reloadSharedAccount() throws IOException {
        usernameField.setText("");
        passwordField.setText("");
        Optional<SharedAccount.Credentials> saved = SharedAccount.load();
        if (!saved.isPresent()) {
            return false;
        }
        try (SharedAccount.Credentials credentials = saved.get()) {
            char[] password = credentials.copyPassword();
            try {
                usernameField.setText(credentials.getUsername());
                passwordField.setText(String.valueOf(password));
            } finally {
                Arrays.fill(password, '\0');
            }
        }
        return true;
    }

    private void editSharedAccount() {
        try {
            SharedAccount.showDialog(this);
            reloadSharedAccount();
        } catch (IOException ex) {
            showError("Could not load the shared account.", ex);
        }
    }

    private void initialiseAttachmentsFolder() {
        try {
            Path folder = attachmentsPath();
            Files.createDirectories(folder);
            appendStatus(
                    "Attachments folder ready: "
                    + folder);
        } catch (IOException | IllegalArgumentException ex) {
            appendStatus(
                    "Could not create the attachments folder: "
                    + ex.getMessage());
        }
    }

    private void loginAndLoadOptions(ActionEvent ignored) {
        if (managedAccount) {
            try {
                if (!reloadSharedAccount()) {
                    showValidation("Set the shared Toolkit account before running.");
                    return;
                }
            } catch (IOException ex) {
                showError("Could not read the shared account. No login was attempted.", ex);
                return;
            }
        }
        final String serverIp;
        final Properties settings;
        final Path workbook;
        final Path attachments;
        final boolean headless = headlessBox.isSelected();
        try {
            settings = settingsFromFields();
            workbook = requestsPath();
            attachments = attachmentsPath();
            if (!Files.isRegularFile(workbook)) {
                showValidation("Requests workbook was not found. Choose an existing .xlsx file.");
                return;
            }
            serverIp = TuarServer.normalizeIp(
                    serverIpField.getText());
            serverIpField.setText(serverIp);
        } catch (IllegalArgumentException ex) {
            showValidation(ex.getMessage());
            return;
        }
        final String username = usernameField.getText().trim();
        final char[] password = passwordField.getPassword();
        final String loginType = String.valueOf(
                loginTypeBox.getSelectedItem());
        final boolean rememberLogin = saveLoginBox.isSelected();

        if (username.isEmpty() || password.length == 0) {
            showValidation("Enter a username and password.");
            Arrays.fill(password, '\0');
            return;
        }

        setBusy(true);
        setStatus("Preparing Chrome and logging in...");

        new SwingWorker<FormOptions, String>() {
            @Override
            protected FormOptions doInBackground() throws Exception {
                Consumer<String> status = this::publish;
                try {
                    tuarBrowser.setHeadless(headless);
                    FormOptions options = tuarBrowser.loginAndLoadOptions(
                            serverIp,
                            username,
                            password,
                            loginType,
                            status);
                    try {
                        if (!managedAccount && rememberLogin) {
                            credentialStore.save(
                                    serverIp,
                                    username,
                                    password,
                                    loginType);
                            publish("Encrypted login saved: "
                                    + credentialStore.getFile());
                        } else if (!managedAccount) {
                            credentialStore.delete();
                            publish("Previously saved login deleted.");
                        }
                        settingsStore.save(settings);
                    } catch (IOException ex) {
                        publish("Login succeeded, but settings could not "
                                + "all be saved: " + ex.getMessage());
                    }
                    return options;
                } finally {
                    Arrays.fill(password, '\0');
                }
            }

            @Override
            protected void process(List<String> messages) {
                if (!messages.isEmpty()) {
                    appendStatus(messages.get(messages.size() - 1));
                }
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    FormOptions options = get();
                    if (!managedAccount && !rememberLogin) {
                        passwordField.setText("");
                    }
                    appendStatus(
                            "Login succeeded and New Request options loaded.");
                    loadAndSubmitWorkbook(options, workbook, attachments);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showError("The operation was cancelled.", ex);
                } catch (ExecutionException ex) {
                    showError(
                            "Login or New Request loading failed.",
                            taskFailure(ex));
                }
            }
        }.execute();
    }

    private void loadAndSubmitWorkbook(FormOptions options, Path workbook, Path attachments) {
        final List<FormData> requests;
        try {
            RequestWorkbookStore.refreshOptions(
                    workbook, options);
            appendStatus(
                    "Excel Options and dropdown lists updated from TUAR.");
            requests = RequestWorkbookStore.load(workbook, options, attachments);
            appendStatus(
                    workbook.getFileName() + " validated: " + requests.size()
                    + " enabled request(s).");
        } catch (IOException | IllegalArgumentException ex) {
            showError(
                    "Could not update or load " + workbook.getFileName() + ". Close the "
                    + "workbook in Excel and try again.",
                    ex);
            return;
        }
        if (requests.isEmpty()) {
            showValidation(
                    "No enabled requests were found in " + workbook.getFileName() + ".");
            return;
        }

        setBusy(true);
        setStatus(
                "Starting automatic Excel batch submission for "
                + requests.size() + " request(s)...");

        new SwingWorker<BatchResult, String>() {
            @Override
            protected BatchResult doInBackground() {
                return tuarBrowser.submitBatch(
                        requests, this::publish);
            }

            @Override
            protected void process(List<String> messages) {
                if (!messages.isEmpty()) {
                    appendStatus(messages.get(messages.size() - 1));
                }
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    BatchResult result = get();
                    appendStatus(
                            "Excel batch completed: "
                            + result.getSubmittedCount()
                            + " submitted, "
                            + result.getSkippedRequests().size()
                            + " skipped.");
                    for (BatchResult.SkippedRequest skipped
                            : result.getSkippedRequests()) {
                        appendStatus(
                                "Skipped Excel row "
                                + skipped.getWorkbookRow()
                                + " - " + skipped.getService()
                                + " - " + skipped.getReason());
                    }
                    showBatchSummary(result);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showError(
                            "Excel batch submission was cancelled.", ex);
                } catch (ExecutionException ex) {
                    showError(
                            "Excel batch stopped before all rows were sent.",
                            taskFailure(ex));
                }
            }
        }.execute();
    }

    private void showBatchSummary(BatchResult result) {
        StringBuilder message = new StringBuilder();
        message.append("Submitted: ")
                .append(result.getSubmittedCount())
                .append("\nSkipped: ")
                .append(result.getSkippedRequests().size());
        if (!result.getSkippedRequests().isEmpty()) {
            message.append("\n\nSkipped requests:\n");
            for (BatchResult.SkippedRequest skipped
                    : result.getSkippedRequests()) {
                message.append("\nExcel row ")
                        .append(skipped.getWorkbookRow())
                        .append("\nService: ")
                        .append(skipped.getService())
                        .append("\nReason: ")
                        .append(skipped.getReason())
                        .append("\n");
            }
        }

        JTextArea summary = new JTextArea(
                message.toString(), 16, 86);
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(summary);
        JOptionPane.showMessageDialog(
                this,
                scroll,
                "Excel Batch Completed",
                result.getSkippedRequests().isEmpty()
                        ? JOptionPane.INFORMATION_MESSAGE
                        : JOptionPane.WARNING_MESSAGE);
    }

    private Path requestsPath() {
        return SettingsStore.workbookPath(workbookField.getText());
    }

    private Path attachmentsPath() {
        return SettingsStore.attachmentsPath(attachmentsField.getText());
    }

    private static JPanel withBrowseButton(JTextField field, JButton button) {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        serverIpField.setEnabled(!busy);
        usernameField.setEnabled(!busy);
        passwordField.setEnabled(!busy);
        loginTypeBox.setEnabled(!busy);
        saveLoginBox.setEnabled(!busy);
        showPasswordBox.setEnabled(!busy);
        saveSettingsButton.setEnabled(!busy);
        forgetLoginButton.setEnabled(!busy);
        sharedAccountButton.setEnabled(!busy);
        workbookField.setEnabled(!busy);
        attachmentsField.setEnabled(!busy);
        browseWorkbookButton.setEnabled(!busy);
        browseAttachmentsButton.setEnabled(!busy);
        headlessBox.setEnabled(!busy);
    }

    private void setStatus(String message) {
        System.out.println("[STATUS] " + message);
        statusArea.setText(message);
    }

    private void appendStatus(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        System.out.println("[STATUS] " + message);
        if (statusArea.getText().trim().isEmpty()) {
            statusArea.setText(message);
        } else {
            statusArea.append("\n" + message);
        }
        statusArea.setCaretPosition(
                statusArea.getDocument().getLength());
    }

    private void showValidation(String message) {
        JOptionPane.showMessageDialog(
                this,
                message,
                "Validation",
                JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String message, Throwable error) {
        String detail = error == null ? "" : error.getMessage();
        appendStatus(message
                + (detail == null ? "" : ": " + detail));
        JOptionPane.showMessageDialog(
                this,
                message + (detail == null || detail.isEmpty()
                        ? "" : "\n" + detail),
                "Error",
                JOptionPane.ERROR_MESSAGE);
    }

    private static void addSection(
            JPanel panel,
            GridBagConstraints gbc,
            int row,
            String title) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        JPanel section = new JPanel(new BorderLayout(8, 0));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(
                Font.BOLD, 15f));
        section.add(label, BorderLayout.WEST);
        section.add(new JSeparator(), BorderLayout.CENTER);
        panel.add(section, gbc);
        gbc.gridwidth = 1;
    }

    private static void addRow(
            JPanel panel,
            GridBagConstraints gbc,
            int row,
            String label,
            java.awt.Component component) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(component, gbc);
    }

    private static Throwable taskFailure(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }

    private static String displayedTitle() {
        return SharedAccount.isManaged()
                ? "Request TUAR | TrueOps Toolkit v" + System.getProperty("trueops.version", APP_VERSION)
                : "Auto Request TUAR v" + APP_VERSION;
    }

    public static void main(String[] args) {
        if (args.length > 0
                && "--check-driver".equals(args[0])) {
            try {
                System.out.println(ChromeDriverManager.prepare(
                        System.out::println));
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                System.exit(1);
            }
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(
                        UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Cross-platform Swing look and feel remains available.
            }
            AutoRequestTUAR application =
                    new AutoRequestTUAR();
            application.setVisible(true);
            if (!SharedAccount.isManaged()) {
                AutoUpdateManager.checkForUpdatesAtStartup(
                        application,
                        APP_VERSION,
                        () -> application.loginButton.isEnabled());
            }
            if (Boolean.getBoolean(AUTORUN_PROPERTY)) {
                application.loginButton.doClick();
            }
        });
    }
}
