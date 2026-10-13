package com.java.myapp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.UIManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/** Managed TK07 module entry; original standalone files are not read or modified. */
public final class TK07_V3_TrueCorp {
    private TK07_V3_TrueCorp() { }

    public static void main(String[] args) throws IOException {
        try { UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel"); }
        catch (Exception ignored) { }
        Tk07Workflow.clearPublishedResult();
        Tk07Settings settings = Tk07Settings.read(Paths.get("settings.csv"));
        String driverPath = System.getProperty("webdriver.chrome.driver", "").trim();
        if (driverPath.isEmpty()) throw new IOException("Open TK07 Review from the Toolkit so it can prepare the shared ChromeDriver.");
        Path downloads = Files.createTempDirectory(Files.createDirectories(Paths.get("downloads").toAbsolutePath().normalize()), "run-");
        final AtomicReference<WebDriver> owned = new AtomicReference<>();
        Thread cleanup = new Thread(() -> closeOwned(owned), "tk07-browser-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            Map<String, Object> preferences = new HashMap<>();
            preferences.put("download.default_directory", downloads.toString());
            preferences.put("download.prompt_for_download", false);
            preferences.put("download.directory_upgrade", true);
            preferences.put("plugins.always_open_pdf_externally", true);
            ChromeOptions options = new ChromeOptions();
            options.setExperimentalOption("prefs", preferences);
            WebDriver driver = new ChromeDriver(options);
            owned.set(driver);
            PromsSignIn.signIn(driver, PortalUrls.login(), settings.username(), settings.password(), 120, Tk07Workflow::progress);
            Tk07Workflow.run(driver, PortalUrls.pending(), settings.search(), settings.approvalDescription(),
                    settings.rejectionDescription(), downloads, 60);
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("TK07 Review failed. Check the shared ChromeDriver, portal and task log before retrying.", failure); }
        finally {
            closeOwned(owned);
            try { Runtime.getRuntime().removeShutdownHook(cleanup); }
            catch (IllegalStateException ignored) { }
        }
    }

    private static void closeOwned(AtomicReference<WebDriver> owned) {
        WebDriver driver = owned.getAndSet(null);
        if (driver != null) {
            try { driver.quit(); }
            catch (RuntimeException ignored) { }
        }
    }
}
