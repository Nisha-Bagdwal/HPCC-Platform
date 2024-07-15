package framework.utility;

import framework.config.Config;
import framework.config.URLConfig;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.time.Duration;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Common {

    public static WebDriver driver;
    public static Logger errorLogger = setupLogger("error");
    public static Logger specificLogger;

    public static void checkTextPresent(String text, String page) {
        try {
            WebElement element = Common.waitForElement(By.xpath("//*[text()='" + text + "']"));
            if (element != null) {
                String msg = "Success: " + page + ": Text present: " + text;
                logDetail(msg);
            }
        } catch (TimeoutException ex) {
            String errorMsg = "Failure: " + page + ": Text not present: " + text;
            logError(errorMsg);
        }
    }

    public static void openWebPage(String url) {
        try {
            driver.get(url);
            driver.manage().window().maximize();
            sleep();
        } catch (Exception ex) {
            Common.logError("Error in opening web page: " + url);
        }
    }

    public static void sleep() {
        try {
            Thread.sleep(Duration.ofSeconds(Config.WAIT_TIME_IN_SECONDS));
        } catch (InterruptedException e) {
            Common.logError("Error in sleep: " + e.getMessage());
        }
    }

    public static void sleepWithTime(int seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Common.logError("Error in sleep: " + e.getMessage());
        }
    }

    public static boolean isRunningOnLocal() {
        return System.getProperty("os.name").startsWith(Config.LOCAL_OS) && System.getenv("USERPROFILE").startsWith(Config.LOCAL_USER_PROFILE);
    }

    public static String getIP() {

        return isRunningOnLocal() ? URLConfig.LOCAL_IP : URLConfig.GITHUB_ACTION_IP;
    }

    public static WebElement waitForElement(By locator) {
        return new WebDriverWait(driver, Duration.ofSeconds(Config.WAIT_TIME_THRESHOLD_IN_SECONDS)).until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    public static void waitForElementToBeClickable(WebElement element) {
        new WebDriverWait(driver, Duration.ofSeconds(Config.WAIT_TIME_THRESHOLD_IN_SECONDS)).until(ExpectedConditions.elementToBeClickable(element));
    }

    // aria-disabled is a standard attribute and is widely used in HTML to indicate whether an element is disabled. It is system-defined, meaning it is part of the standard HTML specifications and not a custom class or attribute that might change frequently.
    // By using aria-disabled, you ensure that the check for the disabled state is consistent and less likely to break due to UI changes. Custom class names or attributes defined by developers can change frequently during updates or redesigns, but standard attributes like aria-disabled are much more stable.

    public static void waitForElementToBeDisabled(WebElement element) {
        new WebDriverWait(driver, Duration.ofSeconds(Config.WAIT_TIME_THRESHOLD_IN_SECONDS))
                .until(ExpectedConditions.attributeContains(element, "aria-disabled", "true"));
    }

    public static void logError(String message) {
        System.err.println(message);
        errorLogger.severe(message);
    }

    public static void logDebug(String message) {
        System.out.println(message);

        if (specificLogger != null && specificLogger.getLevel() == Level.INFO) {
            specificLogger.info(message);
        }

        if (specificLogger != null && specificLogger.getLevel() == Level.FINE) {
            specificLogger.fine(message);
        }
    }

    public static void logDetail(String message) {
        System.out.println(message);

        if (specificLogger != null && specificLogger.getLevel() == Level.FINE) {
            specificLogger.fine(message);
        }
    }

    public static void initializeLoggerAndDriver() {
        specificLogger = setupLogger(Config.LOG_LEVEL);
        driver = setupWebDriver();
    }

    private static WebDriver setupWebDriver() {

        try {
            ChromeOptions chromeOptions = new ChromeOptions();
            chromeOptions.addArguments("--headless"); // sets the ChromeDriver to run in headless mode, meaning it runs without opening a visible browser window.
            chromeOptions.addArguments("--no-sandbox"); // disables the sandbox security feature in Chrome.
            chromeOptions.addArguments("--log-level=3"); // sets the log level for the ChromeDriver. Level 3 corresponds to errors only.

            System.setProperty("webdriver.chrome.silentOutput", "true"); // suppresses the logs generated by the ChromeDriver

            if (Common.isRunningOnLocal()) {
                System.setProperty("webdriver.chrome.driver", Config.PATH_LOCAL_CHROME_DRIVER); // sets the system property to the path of the ChromeDriver executable.
            } else {
                System.setProperty("webdriver.chrome.driver", Config.PATH_GH_ACTION_CHROME_DRIVER);
            }

            RemoteWebDriver driver = new ChromeDriver(chromeOptions);

            Capabilities caps = driver.getCapabilities();
            String browserName = caps.getBrowserName();
            String browserVersion = caps.getBrowserVersion();
            System.out.println(browserName + " " + browserVersion);

            return driver;
        } catch (Exception ex) {
            errorLogger.severe("Failure: Error in setting up web driver: " + ex.getMessage());
        }

        return null;
    }

    private static Logger setupLogger(String logLevel) {

        if (logLevel.isEmpty()) {
            return null;
        }

        Logger logger = Logger.getLogger(logLevel);
        logger.setUseParentHandlers(false); // Disable console logging

        try {
            if (logLevel.equalsIgnoreCase("error")) {
                FileHandler errorFileHandler = new FileHandler(Config.LOG_FILE_ERROR);
                errorFileHandler.setFormatter(new SimpleFormatter());
                logger.addHandler(errorFileHandler);
                logger.setLevel(Level.SEVERE);
            } else if (logLevel.equalsIgnoreCase("debug")) {
                FileHandler debugFileHandler = new FileHandler(Config.LOG_FILE_DEBUG);
                debugFileHandler.setFormatter(new SimpleFormatter());
                logger.addHandler(debugFileHandler);
                logger.setLevel(Level.INFO);
            } else if (logLevel.equalsIgnoreCase("detail")) {
                FileHandler detailFileHandler = new FileHandler(Config.LOG_FILE_DETAIL);
                detailFileHandler.setFormatter(new SimpleFormatter());
                logger.addHandler(detailFileHandler);
                logger.setLevel(Level.FINE);
            }
        } catch (IOException e) {
            System.err.println("Failure: Failed to setup logger: " + e.getMessage());
        }

        Logger.getLogger("org.openqa.selenium").setLevel(Level.OFF); // Turn off all logging from the Selenium WebDriver.
        return logger;
    }

    public static String[] getAttributeList(WebElement webElement, String pageName) {
        try {
            JavascriptExecutor executor = (JavascriptExecutor) driver;
            Object attributes = executor.executeScript("var items = {}; for (index = 0; index < arguments[0].attributes.length; ++index) { items[arguments[0].attributes[index].name] = arguments[0].attributes[index].value }; return items;", webElement);
            return attributes.toString().replaceAll("[{}]", "").split(", ");
        } catch (Exception ex) {
            Common.logError("Failure: " + pageName + ": Error in getting arguments list for web element: " + webElement.getText() + "Error: " + ex.getMessage());
        }

        return new String[0];
    }
}
