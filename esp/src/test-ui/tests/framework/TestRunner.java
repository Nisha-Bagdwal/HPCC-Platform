package framework;

import framework.config.Config;
import framework.config.TestClasses;
import framework.model.TestClass;
import framework.setup.TestInjector;
import framework.utility.Common;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.testng.TestNG;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class TestRunner {
    public static void main(String[] args) {

        Logger logger = setupLogger();
        WebDriver driver = setupWebDriver();

        TestNG testng = new TestNG();
        testng.setTestClasses(loadClasses());
        testng.addListener(new TestInjector(logger, driver));
        testng.run();
        driver.quit();
    }

    private static WebDriver setupWebDriver() {

        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments("--headless"); // sets the ChromeDriver to run in headless mode, meaning it runs without opening a visible browser window.
        chromeOptions.addArguments("--no-sandbox"); // disables the sandbox security feature in Chrome.
        chromeOptions.addArguments("--log-level=3"); // sets the log level for the ChromeDriver. Level 3 corresponds to errors only.

        System.setProperty("webdriver.chrome.silentOutput", "true"); // suppresses the logs generated by the ChromeDriver

        WebDriver driver;

        if (Common.isRunningOnLocal()) {
            System.setProperty("webdriver.chrome.driver", Config.PATH_LOCAL_CHROME_DRIVER); // sets the system property to the path of the ChromeDriver executable.
            try {
                driver = new RemoteWebDriver(URI.create(Config.LOCAL_SELENIUM_SERVER).toURL(), chromeOptions);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        } else {
            System.setProperty("webdriver.chrome.driver", Config.PATH_GH_ACTION_CHROME_DRIVER);
            driver = new ChromeDriver(chromeOptions);
        }

        //Capabilities caps = ((RemoteWebDriver) driver).getCapabilities();
        //String browserName = caps.getBrowserName();
        //String browserVersion = caps.getBrowserVersion();
        //System.out.println(browserName+" "+browserVersion);

        return driver;
    }

    private static Class<?>[] loadClasses() {

        List<Class<?>> classes = new ArrayList<>();
        for (TestClass testClass : TestClasses.testClassesList) {
            try {
                classes.add(Class.forName(testClass.getPath()));
            } catch (ClassNotFoundException e) {
                System.err.println(e.getMessage());
            }
        }

        return classes.toArray(new Class<?>[0]);
    }

    private static Logger setupLogger() {
        Logger logger = Logger.getLogger(TestRunner.class.getName());
        try {
            FileHandler fileHandler = new FileHandler(Config.LOG_FILE);
            SimpleFormatter formatter = new SimpleFormatter();
            fileHandler.setFormatter(formatter);
            logger.addHandler(fileHandler);
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
        } catch (IOException e) {
            System.err.println("Failed to setup logger: " + e.getMessage());
        }

        Logger.getLogger("org.openqa.selenium").setLevel(Level.OFF); // turn off all logging from the Selenium WebDriver.
        return logger;
    }
}
