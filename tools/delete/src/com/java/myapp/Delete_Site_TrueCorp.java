package com.java.myapp;

import java.awt.Toolkit;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import java.io.IOException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

public class Delete_Site_TrueCorp {

    public static void main(String[] args) throws IOException {
        Dialog.setLAF();
        PathFolder Folder = new PathFolder();
        PromsSettings settings = PromsSettings.readLogin(Folder.getSettings(), false);
        List<String[]> references = PromsSettings.readReferences(Folder.getListWorkOrder(), 2);
        final AtomicReference<WebDriver> ownedDriver = new AtomicReference<>();
        final Timer timer = new Timer("delete-site-window-monitor", true);
        Thread cleanupHook = new Thread(() -> {
            timer.cancel();
            closeOwnedDriver(ownedDriver);
        }, "delete-site-browser-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanupHook);
        try {
            System.setProperty("webdriver.chrome.driver", Folder.getChromedriver());

            WebDriver driver = new ChromeDriver();
            ownedDriver.set(driver);

            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        // ตรวจสอบว่าหน้าต่างยังเปิดอยู่หรือไม่
                        if (driver.getWindowHandle() != null) {
                            Toolkit.getDefaultToolkit().beep(); // ส่ง beep ถ้าหน้าต่างยังเปิดอยู่
                        }
                    } catch (WebDriverException e) {
                        System.out.println("Browser window is closed. Stopping the timer...");
                        timer.cancel(); // หยุด Timer เมื่อพบว่าหน้าต่างปิดแล้ว
                        closeOwnedDriver(ownedDriver);
                    }
                }
            }, 0, 5000); // beep ทุกๆ 5 วินาที

            driver.get("https://proms.truecorp.co.th/proms/login/");
            driver.manage().window().maximize();

            WebElement username = driver.findElement(By.id("username"));
            WebElement password = driver.findElement(By.id("password"));
            username.sendKeys(settings.get("User"));
            password.sendKeys(settings.get("Password"));
            WebElement signInButton = driver.findElement(By.id("btn_signin"));
            new WebDriverWait(driver, 120).until(ExpectedConditions.stalenessOf(signInButton));
            System.out.println("The user submitted the sign-in form.");
            Thread.sleep(500);

            for (String[] reference : references) {
                WebDriverWait wait = new WebDriverWait(driver, 500);
                driver.get("https://proms.truecorp.co.th/proms/workorder");
                Thread.sleep(1000);
                wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("swal2-image")));

                System.out.println("Loading complete, proceeding with next steps.");

                WebElement refNoInput = wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                By.xpath("//label[text()='Ref No.']/following-sibling::input")
                        )
                );
                refNoInput.sendKeys(reference[0]);

                WebElement searchButton = wait.until(
                        ExpectedConditions.elementToBeClickable(By.xpath("//button[@data-ng-click='search()']"))
                );
                searchButton.click();
                Thread.sleep(1000);

                wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("swal2-image")));

                System.out.println("Loading complete, proceeding with next steps.");

                         WebElement woStatusSpan = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//span[text()='WO Status']")
            ));

            // คลิกที่ <span>
            woStatusSpan.click();

            // เพิ่มดีเลย์หลังคลิก
            Thread.sleep(1000);

      WebElement button = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[.//span[text()='200']]")
            ));

            // คลิกปุ่ม
            button.click();
                  Thread.sleep(1000);
               WebElement deleteButton = driver.findElement(By.xpath("//a[@data-ng-click='deleteWo(row.woId)']"));

            // ใช้ JavaScriptExecutor เพื่อคลิก element ที่ซ่อน
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("arguments[0].click();", deleteButton);

                Thread.sleep(1000);
                WebElement dropdown = driver.findElement(By.id("template_error_type"));

                Select select = new Select(dropdown);

                select.selectByVisibleText("Other");

                Thread.sleep(1000);

                WebElement textarea = driver.findElement(By.name("commentDesc"));

                textarea.sendKeys(reference[1]);

                Thread.sleep(1000);
                WebElement okButton = driver.findElement(By.xpath("//button[@ng-click='paOkModal()']"));
                okButton.click();
                Thread.sleep(1000);
                okButton = wait.until(
                        ExpectedConditions.elementToBeClickable(By.xpath("//button[@ng-click='paConfirmModal()']"))
                );

                okButton.click();

                Thread.sleep(1000);

                WebElement Success = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//i[@class='glyphicon glyphicon-ok']/ancestor::button")
                ));

                Success.click();
                wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("swal2-image")));

                System.out.println("Loading complete, proceeding with next steps.");
            }

        } catch (Exception ex) {
            String advice = ownedDriver.get() == null
                    ? " Check Chrome and update the shared ChromeDriver in TrueOps Toolkit settings." : "";
            throw new IOException("Delete Site failed." + advice + " See the cause for details.", ex);
        } finally {
            timer.cancel();
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
