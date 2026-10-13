import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/** Validates each module's original Selenium client against a local test page. */
public final class SeleniumClientCheck {
    public static void main(String[] args) throws Exception {
        System.setProperty("webdriver.chrome.driver", args[0]);
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-first-run", "--no-default-browser-check", "--disable-background-networking",
                "--disable-component-update", "--disable-sync", "--disable-extensions",
                "--host-resolver-rules=MAP * 127.0.0.1, EXCLUDE localhost");
        ChromeDriver driver = null;
        StringBuilder report = new StringBuilder();
        report.append("Module: ").append(args[1]).append("\n");
        report.append("Test: original Selenium3 libraries, supplied packaged driver, local data page\n");
        try {
            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(20, TimeUnit.SECONDS);
            report.append("Capabilities: ").append(driver.getCapabilities()).append("\n");
            driver.get("data:text/html,<html><head><title>TrueOps offline Selenium check</title></head>"
                    + "<body><h1 id='status'>Local page loaded</h1><input id='entry'>"
                    + "<button id='test' onclick=\"document.getElementById('status').innerHTML=document.getElementById('entry').value\">Test local DOM</button></body></html>");
            if (!"TrueOps offline Selenium check".equals(driver.getTitle())) throw new IllegalStateException("Wrong page title");
            driver.findElement(By.id("entry")).sendKeys("TRUEOPS_LOCAL_PASS");
            driver.findElement(By.id("test")).click();
            if (!"TRUEOPS_LOCAL_PASS".equals(driver.findElement(By.id("status")).getText())) throw new IllegalStateException("Local DOM test failed");
            report.append("Checks: session creation, navigation, title, findElement, sendKeys, click, getText PASS\n");
        } finally {
            if (driver != null) driver.quit();
        }
        report.append("Cleanup: own Selenium session quit\nResult: PASS\n");
        Files.createDirectories(Paths.get(args[2]).getParent());
        Files.write(Paths.get(args[2]), report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(report.toString());
    }
}
