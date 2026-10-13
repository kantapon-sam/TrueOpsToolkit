package com.java.myapp;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class ApproveProms_TrueCorp_V3 {

    public static void main(String[] args) throws IOException {
        Dialog.setLAF();
        ApprovePromsWorkflow.clearPublishedResult();
        PathFolder folder = new PathFolder();
        PromsSettings settings = PromsSettings.readLogin(folder.getSettings(), true);
        final AtomicReference<WebDriver> ownedDriver = new AtomicReference<>();
        Thread cleanupHook = new Thread(() -> closeOwnedDriver(ownedDriver),
                "approve-proms-browser-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanupHook);
        try {
            System.setProperty("webdriver.chrome.driver", folder.getChromedriver());
            WebDriver driver = new ChromeDriver();
            ownedDriver.set(driver);
            String pendingUrl = PortalUrls.pending();
            PromsSignIn.signIn(driver, PortalUrls.login(), settings.get("User"),
                    settings.get("Password"), 300, ApprovePromsWorkflow::progress);
            driver.get(pendingUrl);
            ApprovePromsWorkflow.run(driver, pendingUrl, settings.get("Search"),
                    settings.get("Description"), 60);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            String advice = ownedDriver.get() == null
                    ? " Check Chrome and update the shared ChromeDriver in TrueOps Toolkit settings."
                    : " Check the PROMS page and the log before trying again.";
            throw new IOException("Approve PROMS failed." + advice, ex);
        } finally {
            closeOwnedDriver(ownedDriver);
            try {
                Runtime.getRuntime().removeShutdownHook(cleanupHook);
            } catch (IllegalStateException ignored) {
                // The shutdown hook owns cleanup while the JVM is stopping.
            }
        }
    }

    private static void closeOwnedDriver(AtomicReference<WebDriver> ownedDriver) {
        WebDriver driver = ownedDriver.getAndSet(null);
        if (driver != null) {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
                // The browser or its driver may already have been closed.
            }
        }
    }
}
