package framework.pages;

import framework.config.Config;
import framework.setup.LoggerHolder;
import framework.setup.WebDriverHolder;
import framework.utility.Common;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

public abstract class BaseTableTest<T> {

    protected abstract String getPageName();

    protected abstract String getPageUrl();

    protected abstract String getJsonFilePath();

    protected abstract String[] getColumnNames();

    protected abstract String[] getColumnKeys();

    protected abstract String getUniqueKeyName();

    protected abstract String getUniqueKey();

    protected abstract String[] getColumnKeysWithLinks();

    protected abstract Object parseDataUIValue(Object dataUIValue, String columnName, Object dataIDUIValue, Logger logger);

    protected abstract Object parseDataJSONValue(Object dataJSONValue, String columnName, Object dataIDUIValue, Logger logger);

    protected abstract List<T> parseJson(String filePath) throws Exception;

    protected abstract Object getColumnDataFromJson(T object, String columnKey);

    protected abstract void sortJsonUsingSortOrder(String currentSortOrder, List<T> jsonObjects, String columnKey);

    protected abstract String getCurrentPage(WebDriver driver);

    protected void testPage() {
        try {
            WebDriver driver = WebDriverHolder.getDriver();
            Common.openWebPage(driver, getPageUrl());
            Logger logger = LoggerHolder.getLogger();

            testForAllText(driver, logger);
            testContentAndSortingOrder(driver, logger);
            testLinksInTable(driver, logger);

        } catch (Exception ex) {
            System.out.println(ex.getMessage());
        }
    }

    private void testLinksInTable(WebDriver driver, Logger logger) {

        for (String columnKey : getColumnKeysWithLinks()) {

            List<Object> values = getDataFromUIUsingColumnKey(driver, columnKey);

            int i = 1;

            for (Object value : values) {
                String name = value.toString().trim();
                //WebElement element = driver.findElement(By.xpath("//div[contains(text(), '"+name+"')]/.."));
                WebElement element = waitForElement(driver, By.xpath("//div[contains(text(), '" + name + "')]/.."));
                String href = element.findElement(By.tagName("a")).getAttribute("href");

                String dropdownValueBefore = getSelectedDropdownValue(driver);

                element.click();

                if (driver.getPageSource().contains(name)) {
                    String msg = "Success: " + getPageName() + ": Link Test Pass for " + i++ + ". " + name + ". URL : " + href;
                    System.out.println(msg);
                } else {
                    String currentPage = getCurrentPage(driver);
                    String errorMsg = "Failure: " + getPageName() + ": Link Test Fail for " + i++ + ". " + name + " page failed. The current navigation page that we landed on is " + currentPage + ". Current URL : " + href;
                    System.err.println(errorMsg);
                    logger.severe(errorMsg);
                }

                driver.navigate().to(getPageUrl());
                driver.navigate().refresh();

                String dropdownValueAfter = getSelectedDropdownValue(driver);

                // Log error if the dropdown value has changed
                if (!dropdownValueBefore.equals(dropdownValueAfter)) {
                    String dropdownErrorMsg = "Failure: " + getPageName() + ": Dropdown value changed after navigating back. Before: " + dropdownValueBefore + ", After: " + dropdownValueAfter;
                    System.err.println(dropdownErrorMsg);
                    logger.severe(dropdownErrorMsg);
                }
            }
        }
    }

    protected WebElement waitForElement(WebDriver driver, By locator) {
        return new WebDriverWait(driver, Duration.ofSeconds(10)).until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    private void testContentAndSortingOrder(WebDriver driver, Logger logger) {
        List<T> jsonObjects = getAllObjectsFromJson(logger);

        if (jsonObjects != null) {
            int numOfItemsJSON = jsonObjects.size();
            clickDropdown(driver, numOfItemsJSON);

            if (testTableContent(driver, logger, jsonObjects)) {
                for (int i = 0; i < getColumnKeys().length; i++) {
                    testTheSortingOrderForOneColumn(driver, logger, jsonObjects, getColumnKeys()[i], getColumnNames()[i]);
                }
            }
        }
    }

    private void testTheSortingOrderForOneColumn(WebDriver driver, Logger logger, List<T> jsonObjects, String columnKey, String columnName) {
        for (int i = 0; i < 3; i++) {

            String currentSortOrder = getCurrentSortingOrder(driver, columnKey);
            List<Object> columnDataFromUI = getDataFromUIUsingColumnKey(driver, columnKey);

            sortJsonUsingSortOrder(currentSortOrder, jsonObjects, columnKey);

            List<Object> columnDataFromJSON = getDataFromJSONUsingColumnKey(columnKey, jsonObjects);
            List<Object> columnDataIDFromUI = getDataFromUIUsingColumnKey(driver, getUniqueKey());

            if (compareData(columnDataFromUI, columnDataFromJSON, columnDataIDFromUI, logger, columnName)) {
                System.out.println("Success: " + getPageName() + ": Values are correctly sorted in " + currentSortOrder + " order by: " + columnName);
            } else {
                String errMsg = "Failure: " + getPageName() + ": Values are not correctly sorted in " + currentSortOrder + " order by: " + columnName;
                System.err.println(errMsg);
                logger.severe(errMsg);
            }
        }
    }

    private String getCurrentSortingOrder(WebDriver driver, String columnKey) {

        WebElement columnHeader = driver.findElement(By.cssSelector("div[data-item-key='" + columnKey + "']"));
        columnHeader.click();

        Common.sleep();

        columnHeader = driver.findElement(By.cssSelector("div[data-item-key='" + columnKey + "']"));

        return columnHeader.getAttribute("aria-sort");
    }

    private List<Object> getDataFromJSONUsingColumnKey(String columnKey, List<T> jsonObjects) {
        List<Object> columnDataFromJSON = new ArrayList<>();
        for (T jsonObject : jsonObjects) {
            columnDataFromJSON.add(getColumnDataFromJson(jsonObject, columnKey));
        }
        return columnDataFromJSON;
    }

    private List<Object> getDataFromUIUsingColumnKey(WebDriver driver, String columnKey) {
        List<WebElement> elements = driver.findElements(By.cssSelector("div[data-automation-key='" + columnKey + "']"));
        List<Object> columnData = new ArrayList<>();
        for (WebElement element : elements) {
            columnData.add(element.getText());
        }
        return columnData;
    }

    void ascendingSortJson(List<T> jsonObjects, String columnKey) {
        jsonObjects.sort(Comparator.comparing(jsonObject -> (Comparable) getColumnDataFromJson(jsonObject, columnKey)));
    }

    void descendingSortJson(List<T> jsonObjects, String columnKey) {
        jsonObjects.sort(Comparator.comparing((Function<T, Comparable>) jsonObject -> (Comparable) getColumnDataFromJson(jsonObject, columnKey)).reversed());
    }

    private boolean testTableContent(WebDriver driver, Logger logger, List<T> jsonObjects) {
        System.out.println("Page: " + getPageName() + ": Number of Objects from Json: " + jsonObjects.size());

        List<Object> columnDataIDFromUI = getDataFromUIUsingColumnKey(driver, getUniqueKey());

        if (jsonObjects.size() != columnDataIDFromUI.size()) {
            Common.sleep();
            columnDataIDFromUI = getDataFromUIUsingColumnKey(driver, getUniqueKey());
        }

        System.out.println("Page: " + getPageName() + ": Number of Objects from UI: " + columnDataIDFromUI.size());

        if (jsonObjects.size() != columnDataIDFromUI.size()) {
            String errMsg = "Failure: " + getPageName() + ": Number of items on UI are not equal to the number of items in JSON" +
                    "\nNumber of Objects from Json: " + jsonObjects.size() +
                    "\nNumber of Objects from UI: " + columnDataIDFromUI.size();
            System.out.println(errMsg);
            logger.severe(errMsg);
            return false;
        }

        boolean pass = true;

        for (int i = 0; i < getColumnKeys().length; i++) {
            List<Object> columnDataFromUI = getDataFromUIUsingColumnKey(driver, getColumnKeys()[i]);
            List<Object> columnDataFromJSON = getDataFromJSONUsingColumnKey(getColumnKeys()[i], jsonObjects);
            if (!compareData(columnDataFromUI, columnDataFromJSON, columnDataIDFromUI, logger, getColumnNames()[i])) {
                pass = false;
            }
        }

        return pass;
    }

    private List<T> getAllObjectsFromJson(Logger logger) {
        String filePath = getJsonFilePath();
        try {
            return parseJson(filePath);
        } catch (Exception e) {
            System.err.println("Exception: " + e.getMessage());
            logger.severe("Failure: Exception: " + e.getMessage());
        }

        logger.severe("Failure: Error in JSON Parsing: " + filePath);
        return null;
    }

    private boolean compareData(List<Object> dataUI, List<Object> dataJSON, List<Object> dataIDUI, Logger logger, String columnName) {

        boolean pass = true;

        for (int i = 0; i < dataUI.size(); i++) {

            Object dataUIValue = dataUI.get(i);
            Object dataJSONValue = dataJSON.get(i);
            Object dataIDUIValue = dataIDUI.get(i);

            dataUIValue = parseDataUIValue(dataUIValue, columnName, dataIDUIValue, logger);
            dataJSONValue = parseDataJSONValue(dataJSONValue, columnName, dataIDUIValue, logger);

            if (!checkValues(dataUIValue, dataJSONValue, dataIDUIValue, logger, columnName)) {
                pass = false;
            }
        }

        if (pass) {
            System.out.println("Success: " + getPageName() + ": Content test passed for column: " + columnName);
        }

        return pass;
    }

    private boolean checkValues(Object dataUIValue, Object dataJSONValue, Object dataIDUIValue, Logger logger, String columnName) {
        if (!dataUIValue.equals(dataJSONValue)) {
            String errMsg = "Failure: " + getPageName() + ": Incorrect " + columnName + " : " + dataUIValue + " in UI for " + getUniqueKeyName() + " : " + dataIDUIValue + ". Correct " + columnName + " is: " + dataJSONValue;
            System.out.println(errMsg);
            logger.severe(errMsg);
            return false;
        }

        return true;
    }

    private void clickDropdown(WebDriver driver, int numOfItemsJSON) {
        WebElement dropdown = driver.findElement(By.id("pageSize"));
        dropdown.click();

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        List<WebElement> options = wait.until(ExpectedConditions.visibilityOfAllElementsLocatedBy(By.cssSelector(".ms-Dropdown-item")));

        int selectedValue = Config.dropdownValues[0];

        // the smallest dropdown value greater than numOfItemsJSON
        for (int value : Config.dropdownValues) {
            if (numOfItemsJSON < value) {
                selectedValue = value;
                break;
            }
        }

        System.out.println("Dropdown selected: " + selectedValue);

        for (WebElement option : options) {
            if (option.getText().equals(String.valueOf(selectedValue))) {
                option.click();
                break;
            }
        }

        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector(".ms-Dropdown-item")));
        driver.navigate().refresh();
        Common.sleep();
    }

    private String getSelectedDropdownValue(WebDriver driver) {
        WebElement dropdown = waitForElement(driver, By.id("pageSize"));
        return dropdown.getText().trim();
    }

    private void testForAllText(WebDriver driver, Logger logger) {
        for (String text : getColumnNames()) {
            Common.checkTextPresent(driver, text, getPageName(), logger);
        }
    }
}

