package org.example;

import io.github.bonigarcia.wdm.WebDriverManager;
import okhttp3.OkHttpClient;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.example.FacebookBot2.*;

public class BotWorker implements Runnable {
    private static final String POST_CONTAINER_XPATH = "//div[@role='article']";
    private static final String[] KEYWORDS = {"help", "advice", "?", "diet", "weight", "pain", "tips", "similar experience", "symptoms", "food", "aching", "success stories"};
    private static final Duration POST_AGE_LIMIT = Duration.ofHours(24);

    private final int id;
    private final FacebookBot2.AccountInfo account;
    private final FacebookBot2.BotConfig config;
    private final AtomicInteger totalInteractions;
    private final Set<String> processedPosts;
    private final OkHttpClient httpClient;

    private WebDriver driver;
    private WebDriverWait wait;

    public BotWorker(int id, FacebookBot2.AccountInfo account, FacebookBot2.BotConfig config,
                     AtomicInteger totalInteractions, Set<String> processedPosts, OkHttpClient httpClient) {
        this.id = id;
        this.account = account;
        this.config = config;
        this.totalInteractions = totalInteractions;
        this.processedPosts = processedPosts;
        this.httpClient = httpClient;
    }

    @Override
    public void run() {
        log(id, info("Starting for " + account.email));
        try {
            initializeDriver();
            loginToFacebook();
            List<String> groupUrls = fetchGroups();
            log(id, ok("Found " + groupUrls.size() + " groups"));
            processGroups(groupUrls);
        } catch (Exception e) {
            log(id, fail("Fatal: " + e.getMessage()));
            e.printStackTrace(System.out);
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
            log(id, info("Shut down"));
        }
    }

    private void initializeDriver() {
        String chromeDriverPath = "C:\\chromedriver\\chromedriver.exe";
        if (new File(chromeDriverPath).exists()) {
            System.setProperty("webdriver.chrome.driver", chromeDriverPath);
        } else {
            WebDriverManager.chromedriver().setup();
        }

        // A fresh, unique profile dir per launch so concurrent workers never share or
        // collide on a profile, and nothing is reused across a reconnect.
        String userDataDir = System.getProperty("java.io.tmpdir")
                + File.separator + "fbagent_w" + id + "_" + System.nanoTime();

        ChromeOptions options = new ChromeOptions();
        List<String> argsList = new ArrayList<>(Arrays.asList(
                "--disable-notifications",
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--remote-allow-origins=*",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
                "--window-size=1920,1080",
                "--disable-extensions",
                // Always incognito: each worker starts with no memory of any previous session.
                "--incognito",
                "--user-data-dir=" + userDataDir
        ));
        if (config.headless) argsList.add("--headless=new");
        options.addArguments(argsList);
        options.setExperimentalOption("excludeSwitches", Collections.singletonList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        System.setProperty("webdriver.chrome.silentOutput", "true");
        java.util.logging.Logger.getLogger("org.openqa.selenium").setLevel(java.util.logging.Level.OFF);

        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    private void loginToFacebook() {
        log(id, info("Logging in..."));
        JavascriptExecutor js = (JavascriptExecutor) driver;

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                driver.get("https://mbasic.facebook.com/login/");
                waitForPageLoad();

                if (isLoggedIn()) {
                    log(id, ok("Already logged in"));
                    driver.get("https://www.facebook.com");
                    return;
                }

                removeOverlays(js);
                dismissAlert();

                log(id, info("Login page: " + driver.getCurrentUrl()));

                WebElement emailField = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//input[@name='email' or @id='email' or @type='email']")));
                emailField.clear();
                emailField.sendKeys(account.email);

                WebElement passField = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//input[@name='pass' or @id='pass' or @type='password']")));
                passField.clear();
                passField.sendKeys(account.password);

                // Prefer a named submit button (www: <button name=login>, mbasic: <input name=login>),
                // but fall back to submitting the form with Enter — some login variants have no element
                // named "login" at all, which is why waiting for one timed out.
                List<WebElement> loginBtns = driver.findElements(By.xpath(
                        "//button[@name='login'] | //input[@name='login'] " +
                        "| //*[@type='submit' and (@name='login' or @aria-label='Log in' or @aria-label='Log In')] " +
                        "| //div[@role='button' and (@aria-label='Log in' or @aria-label='Log In')]"));
                if (!loginBtns.isEmpty()) {
                    js.executeScript("arguments[0].click();", loginBtns.get(0));
                } else {
                    log(id, warn("No login button found; submitting form via Enter key"));
                    passField.sendKeys(Keys.RETURN);
                }
                Thread.sleep(3000);
                log(id, info("After submit: " + driver.getCurrentUrl()));

                waitForCaptchaResolution();

                driver.get("https://www.facebook.com");
                waitForPageLoad();

                if (isLoggedIn()) {
                    log(id, ok("Logged in as " + account.email));
                    return;
                }
                log(id, warn("Login attempt " + attempt + " failed, retrying..."));
            } catch (Exception e) {
                log(id, fail("Login attempt " + attempt + ": " + e.getMessage()));
                if (attempt == maxAttempts)
                    throw new RuntimeException("Login failed after " + maxAttempts + " attempts: " + e.getMessage(), e);
            }
        }
    }

    private boolean isLoggedIn() {
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), \"what's on your mind\")"
                            + " or contains(@placeholder, 'post')"
                            + " or contains(@aria-label, 'Create a post')"
                            + " or contains(@data-pagelet, 'Feed')"
                            + " or contains(@id, 'mbasic_logout')"
                            + " or contains(@href, '/logout')]")));
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    private boolean isSessionValid() {
        try { driver.getCurrentUrl(); return true; } catch (Exception e) { return false; }
    }

    private List<String> fetchGroups() {
        log(id, info("Fetching groups..."));
        Set<String> groupUrls = new HashSet<>();
        JavascriptExecutor js = (JavascriptExecutor) driver;

        try {
            driver.get("https://www.facebook.com/groups/feed?_=" + System.currentTimeMillis());
            Thread.sleep(3000);

            try {
                WebElement seeAll = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//span[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'see all')]")));
                js.executeScript("arguments[0].click();", seeAll);
                Thread.sleep(3000);
            } catch (Exception ignored) {}

            for (int attempt = 1; attempt <= 10; attempt++) {
                List<WebElement> groupLinks = driver.findElements(By.xpath(
                        "//a[contains(@href, '/groups/') and contains(@role, 'link') " +
                        "and not(contains(@href, 'groups/joined')) " +
                        "and not(contains(@href, 'groups/members')) " +
                        "and not(contains(@href, 'groups/discover'))]"));

                for (WebElement link : groupLinks) {
                    try {
                        String href = link.getAttribute("href");
                        if (href != null) {
                            String clean = href.split("\\?")[0].replaceAll("/$", "");
                            if (clean.matches("https://(www|web)\\.facebook\\.com/groups/\\d+/?$")) {
                                groupUrls.add(clean);
                            }
                        }
                    } catch (StaleElementReferenceException ignored) {}
                }

                if (groupUrls.size() >= 70) break;
                js.executeScript("window.scrollBy(0, document.body.scrollHeight);");
                Thread.sleep(3000);
            }
        } catch (Exception e) {
            log(id, fail("Failed to fetch groups: " + e.getMessage()));
        }

        return new ArrayList<>(groupUrls);
    }

    private void processGroups(List<String> groupUrls) {
        for (String groupUrl : groupUrls) {
            if (totalInteractions.get() >= config.maxInteractions) {
                log(id, warn("Max interactions reached, stopping"));
                return;
            }

            log(id, info("Group: " + groupUrl));
            int attempts = 0;
            while (attempts < 3 && totalInteractions.get() < config.maxInteractions) {
                try {
                    if (!isSessionValid()) {
                        log(id, warn("Session dead, reconnecting..."));
                        reconnect();
                    }
                    interactWithGroup(groupUrl);
                    break;
                } catch (Exception e) {
                    attempts++;
                    log(id, fail("Attempt " + attempts + "/3: " + e.getMessage()));
                    if (attempts < 3) sleep(5000);
                }
            }
        }
    }

    private void reconnect() {
        try { driver.quit(); } catch (Exception ignored) {}
        initializeDriver();
        loginToFacebook();
    }

    private void interactWithGroup(String groupUrl) throws Exception {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        driver.get(groupUrl);
        waitForPageLoad();
        dismissAlert();
        removeOverlays(js);

        if (driver.getCurrentUrl().contains("login")) {
            throw new Exception("Session expired");
        }
        waitForCaptchaResolution();
        if (!isGroupPage()) {
            log(id, warn("Not a valid group page, skipping"));
            return;
        }

        selectNewPostsSort(js);

        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath(POST_CONTAINER_XPATH)));
        } catch (TimeoutException e) {
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[contains(@data-pagelet,'Feed')]")));
            } catch (TimeoutException ignored) {}
        }

        List<WebElement> posts = loadPosts(js);
        log(id, info("Loaded " + posts.size() + " posts"));

        for (int i = 0; i < posts.size(); i++) {
            if (totalInteractions.get() >= config.maxInteractions) {
                log(id, warn("Daily limit reached"));
                return;
            }
            try {
                interactWithPost(posts.get(i), i + 1, js);
            } catch (Exception e) {
                log(id, fail("Post #" + (i + 1) + ": " + e.getMessage()));
            }
        }
    }

    private void selectNewPostsSort(JavascriptExecutor js) {
        try {
            if (isNewPostsSelected()) return;

            List<WebElement> triggers = driver.findElements(By.xpath(
                    "//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'most relevant')" +
                    " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'recent activity')" +
                    " or contains(@aria-label, 'Sort')" +
                    " or contains(@aria-label, 'sort')]"));
            if (triggers.isEmpty()) {
                triggers = driver.findElements(By.xpath(
                    "//div[@role='button']//span[contains(text(),'Most')] | " +
                    "//div[@role='button']//span[contains(text(),'Recent')]"));
            }
            if (!triggers.isEmpty()) {
                js.executeScript("arguments[0].scrollIntoView({block: 'center'}); arguments[0].click();", triggers.get(0));
                Thread.sleep(2000);
                dismissAlert();

                List<WebElement> option = driver.findElements(By.xpath(
                        "//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new posts') " +
                        "and not(ancestor::*[contains(@style,'display:none') or contains(@style,'display: none')])]"));
                if (option.isEmpty()) {
                    option = driver.findElements(By.xpath(
                        "//div[@role='menuitem' or @role='option']" +
                        "[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new posts')]"));
                }
                if (!option.isEmpty()) {
                    js.executeScript("arguments[0].click();", option.get(0));
                    Thread.sleep(4000);
                }
            }
        } catch (Exception e) {
            log(id, warn("Sort dropdown interaction failed: " + e.getMessage()));
        }
    }

    private boolean isNewPostsSelected() {
        try {
            return !driver.findElements(By.xpath(
                    "//*[(contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new posts')" +
                    " or contains(@aria-label, 'New posts')" +
                    " or contains(@aria-label, 'new posts'))" +
                    " and (contains(@class, 'selected') or contains(@aria-selected, 'true') or contains(@aria-current, 'true')" +
                    " or ancestor::*[contains(@class, 'selected')])]")).isEmpty();
        } catch (Exception e) { return false; }
    }

    private List<WebElement> loadPosts(JavascriptExecutor js) {
        List<WebElement> posts = new ArrayList<>();
        int prevCount = 0, unchanged = 0;
        int maxScrolls = 40;

        WebElement feedScroller = findFeedScroller();
        boolean hasFeedScroller = feedScroller != null;

        for (int attempt = 1; attempt <= maxScrolls && posts.size() < 20; attempt++) {
            dismissAlert();
            removeOverlays(js);

            posts = driver.findElements(By.xpath(POST_CONTAINER_XPATH));
            if (posts.size() >= 20) break;

            if (posts.size() == prevCount) unchanged++;
            else { unchanged = 0; prevCount = posts.size(); }
            if (unchanged >= 6) break;

            if (hasFeedScroller) {
                js.executeScript("arguments[0].scrollBy(0, arguments[0].clientHeight * 0.8);", feedScroller);
            } else {
                js.executeScript("window.scrollBy(0, 1000);");
            }
            sleep(3000 + ThreadLocalRandom.current().nextInt(2000));
        }

        if (posts.size() < 3) {
            log(id, warn("Only found " + posts.size() + " posts, trying deeper scroll..."));
            for (int i = 0; i < 15; i++) {
                if (hasFeedScroller) {
                    js.executeScript("arguments[0].scrollBy(0, arguments[0].scrollHeight * 0.3);", feedScroller);
                } else {
                    js.executeScript("window.scrollBy(0, 1500);");
                }
                sleep(4000);
                posts = driver.findElements(By.xpath(POST_CONTAINER_XPATH));
                if (posts.size() >= 15) break;
            }
        }

        return posts;
    }

    private WebElement findFeedScroller() {
        try {
            List<WebElement> feeds = driver.findElements(By.xpath("//div[@role='feed']"));
            if (!feeds.isEmpty()) return feeds.get(0);
            feeds = driver.findElements(By.xpath("//div[contains(@data-pagelet,'Feed')]"));
            if (!feeds.isEmpty()) return feeds.get(0);
        } catch (Exception ignored) {}
        return null;
    }

    private void interactWithPost(WebElement post, int postIndex, JavascriptExecutor js) throws Exception {
        dismissAlert();
        removeOverlays(js);

        List<WebElement> posts = driver.findElements(By.xpath(POST_CONTAINER_XPATH));
        if (posts.size() < postIndex) return;
        WebElement currentPost = posts.get(postIndex - 1);

        wait.until(ExpectedConditions.visibilityOf(currentPost));
        js.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", currentPost);
        highlightPost(currentPost);
        sleep(ThreadLocalRandom.current().nextInt(2000, 4000));

        String postId = extractPostId(currentPost);
        synchronized (processedPosts) {
            if (processedPosts.contains(postId)) {
                log(id, warn("Already processed #" + postIndex + " (dup check)"));
                return;
            }
        }

        LocalDateTime postTime = getPostTime(currentPost);
        if (postTime.isBefore(LocalDateTime.now().minus(POST_AGE_LIMIT))) {
            log(id, warn("Post #" + postIndex + " too old"));
            return;
        }

        String postText = cleanPostText(currentPost);
        if (postText.trim().isEmpty() || !containsKeyword(postText)) {
            log(id, warn("Post #" + postIndex + " no matching keywords"));
            return;
        }

        if (isAlreadyLiked(currentPost)) {
            log(id, warn("Post #" + postIndex + " already liked, skipping"));
            return;
        }
        clickLikeButton(currentPost, js);
        sleep(ThreadLocalRandom.current().nextInt(1500, 3000));

        String reply = generateReply(postText);
        if (reply == null || reply.trim().isEmpty()) return;

        postComment(currentPost, reply, js);

        synchronized (processedPosts) { processedPosts.add(postId); }
        totalInteractions.incrementAndGet();

        log(id, ok("Post #" + postIndex + " liked + commented (total: " +
                totalInteractions.get() + "/" + config.maxInteractions + ")"));

        FacebookBot2.logInteraction(postText, reply);
        sleep(ThreadLocalRandom.current().nextInt(4000, 8000));
    }

    private boolean isAlreadyLiked(WebElement post) {
        try {
            List<WebElement> unlikeBtns = post.findElements(By.xpath(
                ".//*[contains(@aria-label,'Unlike') or contains(@aria-label,'unlike')]"));
            if (!unlikeBtns.isEmpty() && unlikeBtns.get(0).isDisplayed()) return true;
            List<WebElement> likedIndicators = post.findElements(By.xpath(
                ".//span[contains(text(),'Liked')] | .//*[contains(@aria-label,'Unlike')]"));
            return !likedIndicators.isEmpty();
        } catch (Exception e) { return false; }
    }

    private void clickLikeButton(WebElement post, JavascriptExecutor js) throws Exception {
        try {
            dismissAlert();
            List<WebElement> btns = post.findElements(By.xpath(
                ".//*[(contains(@aria-label,'Like') or contains(@aria-label,'like')) " +
                "and not(contains(@aria-label,'Unlike') or contains(@aria-label,'unlike'))] " +
                "| .//span[contains(text(),'Like')]/.."));
            if (btns.isEmpty()) {
                btns = driver.findElements(By.xpath(
                    "//*[(contains(@aria-label,'Like') or contains(@aria-label,'like')) " +
                    "and not(contains(@aria-label,'Unlike') or contains(@aria-label,'unlike'))]"));
            }
            if (btns.isEmpty()) throw new Exception("Like button not found");
            WebElement btn = btns.get(0);
            js.executeScript("arguments[0].scrollIntoView({block: 'center'});", btn);
            sleep(500);
            js.executeScript("arguments[0].click();", btn);
        } catch (Exception e) {
            throw new Exception("Failed to like: " + e.getMessage());
        }
    }

    private void postComment(WebElement post, String comment, JavascriptExecutor js) throws Exception {
        dismissAlert();
        removeOverlays(js);

        List<WebElement> commentBtns = post.findElements(By.xpath(
            ".//*[(contains(@aria-label,'Comment') or contains(@aria-label,'comment')) " +
            "and not(contains(@aria-label,'Uncomment') or contains(@aria-label,'uncomment'))] " +
            "| .//span[contains(text(),'Comment')]/.."));
        if (commentBtns.isEmpty()) {
            commentBtns = driver.findElements(By.xpath(
                "//*[(contains(@aria-label,'Comment') or contains(@aria-label,'comment')) " +
                "and not(contains(@aria-label,'Uncomment') or contains(@aria-label,'uncomment'))]"));
        }
        if (commentBtns.isEmpty()) throw new Exception("Comment button not found");
        WebElement btn = commentBtns.get(0);
        js.executeScript("arguments[0].click();", btn);
        sleep(800);

        WebElement textArea = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//div[@role='textbox' and @contenteditable='true']")));

        js.executeScript(
                "arguments[0].focus(); arguments[0].innerText = arguments[1]; " +
                "arguments[0].dispatchEvent(new InputEvent('input', {bubbles: true}));" +
                "arguments[0].dispatchEvent(new Event('change', {bubbles: true}));",
                textArea, comment.replaceAll("[\\r\\n]+", "\n").trim());
        sleep(800);

        dismissAlert();
        js.executeScript("arguments[0].dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, composed: true}));", textArea);
        sleep(1500);
    }

    private String generateReply(String postText) throws Exception {
        String prompt = String.format(
                "Now I want to give a reply to this post on Facebook. But I want you to sketch out the reply to me. " +
                "I want it to be very very friendly and encouraging. Let the first paragraph be directly addressing " +
                "the question or issue raised in the post, Also you can add like a bit of my own experience " +
                "(just draft it out yourself) Then how I've been managing it and how my doctor told me about how " +
                "good an anti Inflammatory diet will help me in this my journey which it has really helped a lot. " +
                "And he was also kind enough to tell me to go get a diet book in Amazon titled, " +
                "\"Anti-Inflammatory Blueprint Chronicle: An Immersive look into Healthy dietary habits for Good Health\" " +
                "(book title should be in uppercase) by Jeremy Nowak. Proceed to refer the person to the book. " +
                "Also make the answer fairly detailed and realistic and helpful. stress on the diet part so that " +
                "the person can be convinced to get the book. Also talk about my own symptoms and how frustrating " +
                "it was. Till my doctor was kind enough to introduce the AIP diet to me. And talk about how it has " +
                "helped me a lot and subdued my symptoms. Also give them other tips too, and when giving another tips, " +
                "talk about stress management and how it's really important. Stress on the need of proper practices " +
                "help ease there symptoms. Also tell her the reasons she should be considering the diet. It should be " +
                "very convincing pls. Pls answer in a fairy detailed here. Also encourage her and cheer her up.\n\n" +
                "Post content: " + postText);

        return FacebookBot2.askAI(httpClient, config.model, prompt);
    }

    // --- Utility methods ---

    private void waitForPageLoad() {
        new WebDriverWait(driver, Duration.ofSeconds(30)).until(
                wd -> ((JavascriptExecutor) wd).executeScript("return document.readyState").equals("complete"));
    }

    private void dismissAlert() {
        try { driver.switchTo().alert().dismiss(); } catch (NoAlertPresentException ignored) {}
    }

    private void removeOverlays(JavascriptExecutor js) {
        js.executeScript(
            "document.querySelectorAll('div[role=\"dialog\"], div[role=\"presentation\"], " +
            "div[class*=\"popup\"], div[class*=\"cookie\"], div[class*=\"modal\"], " +
            "div[class*=\"tooltip\"], div[aria-modal=\"true\"]').forEach(el => el.style.display = 'none');");
        js.executeScript(
            "document.querySelectorAll('[data-pagelet^=\"Dialog\"], [data-pagelet^=\"Modal\"]').forEach(el => el.style.display = 'none');");
    }

    private void highlightPost(WebElement post) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].style.boxShadow='0 0 0 3px rgba(255,0,0,0.5)';", post);
    }

    private boolean isGroupPage() {
        String url = driver.getCurrentUrl();
        if (!url.contains("/groups/") || url.contains("/groups/joined") || url.contains("/groups/members"))
            return false;
        try {
            return wait.until(ExpectedConditions.or(
                ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@role='article']")),
                ExpectedConditions.presenceOfElementLocated(By.xpath("//div[contains(@data-pagelet,'Group')]")),
                ExpectedConditions.presenceOfElementLocated(By.xpath("//h1"))
            )) != null;
        } catch (TimeoutException e) {
            return false;
        }
    }

    private boolean isActiveCaptchaPresent() {
        try {
            // A genuine challenge puts Facebook on a checkpoint/captcha URL.
            String url = driver.getCurrentUrl().toLowerCase(Locale.ROOT);
            if (url.contains("/checkpoint/") || url.contains("captcha")) {
                return true;
            }

            // Otherwise require a *visible* captcha widget. A plain text match anywhere on
            // the page (footer links, hidden markup, etc.) produces false positives, since
            // normal Facebook pages contain words like "security check".
            List<WebElement> candidates = driver.findElements(By.xpath(
                    "//iframe[contains(@src,'captcha') or contains(@src,'recaptcha') or contains(@title,'captcha')] " +
                    "| //img[contains(@alt,'captcha') or contains(@src,'captcha')] " +
                    "| //*[@title='Security Check']"));
            for (WebElement el : candidates) {
                if (el.isDisplayed()) return true;
            }
            return false;
        } catch (Exception e) { return false; }
    }

    private void waitForCaptchaResolution() {
        if (!isActiveCaptchaPresent()) return;

        log(id, warn("CAPTCHA detected! Please solve it in the browser window."));
        log(id, info("Waiting for CAPTCHA to be solved..."));

        int waited = 0;
        while (isActiveCaptchaPresent() && waited < 120) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            waited += 2;
            dismissAlert();
        }

        if (isActiveCaptchaPresent()) {
            log(id, fail("CAPTCHA not solved after 4 minutes. Continuing anyway..."));
        } else {
            log(id, ok("CAPTCHA solved! Continuing..."));
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private String extractPostId(WebElement post) {
        try {
            String id = post.getAttribute("id");
            if (id != null && id.startsWith("hyperfeed_story_")) return id;

            List<WebElement> links = post.findElements(By.xpath(".//a[contains(@href,'/posts/')]"));
            if (!links.isEmpty()) {
                Matcher m = Pattern.compile("/posts/(\\d+)").matcher(links.get(0).getAttribute("href"));
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return "hash_" + Math.abs(post.getLocation().hashCode());
    }

    private LocalDateTime getPostTime(WebElement post) {
        try {
            WebElement el = post.findElement(By.xpath(".//abbr | .//a[contains(@href,'/posts/')]"));
            return parseFacebookTime(el.getAttribute("title") != null ? el.getAttribute("title") : el.getText());
        } catch (Exception e) { return LocalDateTime.now(); }
    }

    private LocalDateTime parseFacebookTime(String t) {
        t = t.replace("\u00b7", "").trim();
        if (t.contains("mins ago")) return LocalDateTime.now().minusMinutes(Integer.parseInt(t.replaceAll("\\D+", "")));
        if (t.contains("hrs ago")) return LocalDateTime.now().minusHours(Integer.parseInt(t.replaceAll("\\D+", "")));
        if (t.contains("Yesterday")) return LocalDateTime.now().minusDays(1);
        try { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a")); }
        catch (Exception e) { return LocalDateTime.now(); }
    }

    private String cleanPostText(WebElement post) {
        try {
            List<WebElement> els = post.findElements(By.xpath(
                    ".//div[@data-ad-preview='message'] | .//div[contains(@data-ad-preview,'message')]"));
            if (els.isEmpty()) {
                els = post.findElements(By.xpath(".//div[not(descendant::div)]"));
            }
            return els.stream().map(WebElement::getText).filter(t -> t.length() > 20)
                    .collect(Collectors.joining("\n"))
                    .replaceAll("\\s+", " ").trim();
        } catch (Exception e) { return ""; }
    }

    private boolean containsKeyword(String text) {
        return Arrays.stream(KEYWORDS).anyMatch(k -> text.toLowerCase().contains(k.toLowerCase()));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
