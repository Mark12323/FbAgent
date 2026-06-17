package org.example;

import io.github.bonigarcia.wdm.WebDriverManager;
import okhttp3.OkHttpClient;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.example.FacebookBot2.*;

public class BotWorker implements Runnable {
    private static final String POST_CONTAINER_XPATH = "//div[@role='feed']/div";
    private static final String[] KEYWORDS = {"help", "advice", "?", "diet", "weight", "pain", "tips", "similar experience", "symptoms", "food", "aching", "success stories"};
    private static final Duration POST_AGE_LIMIT = Duration.ofHours(24);
    private static final Pattern POST_URL_PATTERN = Pattern.compile("/(?:groups/\\d+/)?posts/(\\d+)|/permalink/(\\d+)");
    private static final Pattern RELATIVE_TIME_PATTERN = Pattern.compile("(?i)^(?:about\\s+)?(\\d+)\\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks)\\b.*");
    private static final Pattern ABSOLUTE_TIME_PATTERN = Pattern.compile("(?i)\\b(?:jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december)\\s+\\d{1,2}(?:,\\s*\\d{4})?(?:\\s+at\\s+\\d{1,2}:\\d{2}\\s*(?:am|pm))?\\b");
    private static final Pattern SHORT_ABSOLUTE_TIME_PATTERN = Pattern.compile("(?i)^(today|yesterday)\\s+at\\s+\\d{1,2}:\\d{2}\\s*(?:am|pm)$");
    private static final Duration SORTED_GROUP_POST_LOAD_BUDGET = Duration.ofSeconds(90);
    private static final Duration MANUAL_GROUP_POST_LOAD_BUDGET = Duration.ofSeconds(150);

    private final int id;
    private final FacebookBot2.AccountInfo account;
    private final FacebookBot2.BotConfig config;
    private final AtomicInteger totalInteractions;
    private final Set<String> processedPosts;
    private final OkHttpClient httpClient;

    private WebDriver driver;
    private WebDriverWait wait;

    private static class PostSnapshot {
        final WebElement root;
        final WebElement likeButton;
        final WebElement commentButton;
        final String postId;
        final String text;
        final String timestampText;
        final LocalDateTime postTime;
        final int score;
        final List<String> rejectionReasons;

        PostSnapshot(WebElement root, WebElement likeButton, WebElement commentButton, String postId,
                     String text, String timestampText, LocalDateTime postTime, int score, List<String> rejectionReasons) {
            this.root = root;
            this.likeButton = likeButton;
            this.commentButton = commentButton;
            this.postId = postId;
            this.text = text;
            this.timestampText = timestampText;
            this.postTime = postTime;
            this.score = score;
            this.rejectionReasons = rejectionReasons;
        }
    }

    private static class RejectionSummary {
        int total;
        final Map<String, Integer> reasonCounts = new LinkedHashMap<>();

        void record(List<String> reasons) {
            total++;
            if (reasons == null || reasons.isEmpty()) {
                reasonCounts.merge("unknown", 1, Integer::sum);
                return;
            }
            for (String reason : reasons) reasonCounts.merge(reason, 1, Integer::sum);
        }

        String describe() {
            return reasonCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
        }
    }

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
            if (config.debugPostDetection) {
                new PostDebugCollector(driver, id).capture(groupUrls, config.debugGroupLimit);
                return;
            }
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
                driver.get("https://www.facebook.com/login/");
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
                humanType(emailField, account.email);

                WebElement passField = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//input[@name='pass' or @id='pass' or @type='password']")));
                passField.clear();
                humanType(passField, account.password);

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

                waitForLoginSecurityChallenges();

                if (completeLoginAfterChallenges()) {
                    log(id, ok("Logged in as " + account.email));
                    return;
                }
                if (attempt == maxAttempts) {
                    throw new RuntimeException("Login failed after " + maxAttempts + " attempts");
                }
                log(id, warn("Login attempt " + attempt + " failed, retrying..."));
            } catch (Exception e) {
                log(id, fail("Login attempt " + attempt + ": " + e.getMessage()));
                if (attempt == maxAttempts)
                    throw new RuntimeException("Login failed after " + maxAttempts + " attempts: " + e.getMessage(), e);
            }
        }
    }

    private boolean completeLoginAfterChallenges() {
        waitForCheckpointFollowUpResolution();

        if (isLoggedInMarkerPresent()) return true;
        if (isLoginErrorPresent()) return false;

        try {
            if (driver.getCurrentUrl().toLowerCase(Locale.ROOT).contains("/login")) return false;
            driver.get("https://www.facebook.com");
            waitForPageLoad();
            waitForCheckpointFollowUpResolution();
            return isLoggedIn();
        } catch (Exception e) {
            log(id, warn("Could not complete post-challenge login check: " + e.getMessage()));
            return false;
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

        boolean newPostsSort = selectNewPostsSort(js);

        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath(POST_CONTAINER_XPATH)));
        } catch (TimeoutException e) {
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@role='article']")));
            } catch (TimeoutException ignored) {}
        }

        List<PostSnapshot> posts = loadPosts(js, newPostsSort);
        int totPosts = posts.size();
        int validPosts = 0;
        log(id, info("TOT_POSTS: " + totPosts));
        activity("Loaded " + totPosts + " actionable post candidates from group");

        for (int i = 0; i < posts.size(); i++) {
            if (totalInteractions.get() >= config.maxInteractions) {
                log(id, warn("Daily limit reached"));
                return;
            }
            try {
                if (interactWithPost(posts.get(i), i + 1, js)) {
                    validPosts++;
                }
            } catch (Exception e) {
                log(id, fail("Post #" + (i + 1) + ": " + e.getMessage()));
            }
        }
        log(id, info("VALID_POSTS: " + validPosts + " / TOT_POSTS: " + totPosts));
    }

    private boolean selectNewPostsSort(JavascriptExecutor js) {
        try {
            if (isNewPostsSelected()) return true;

            log(id, info("Looking for sort dropdown..."));
            WebElement trigger = findSortTrigger();

            if (trigger == null) {
                log(id, warn("Sort dropdown not found, continuing with manual post discovery..."));
                return false;
            }

            js.executeScript("arguments[0].scrollIntoView({block: 'center'}); arguments[0].click();", trigger);
            Thread.sleep(2000);
            dismissAlert();

            WebElement newPostsOption = findNewPostsOption();
            if (newPostsOption == null) {
                log(id, warn("'New Posts' option not found, trying keyboard navigation..."));
                try {
                    new Actions(driver).sendKeys(Keys.ARROW_DOWN).perform();
                    Thread.sleep(500);
                    new Actions(driver).sendKeys(Keys.ARROW_DOWN).perform();
                    Thread.sleep(500);
                    new Actions(driver).sendKeys(Keys.RETURN).perform();
                    Thread.sleep(4000);
                    log(id, info("Sort set to New Posts via keyboard"));
                    return true;
                } catch (Exception e2) {
                    log(id, warn("Keyboard navigation also failed: " + e2.getMessage()));
                    return false;
                }
            }

            js.executeScript("arguments[0].click();", newPostsOption);
            Thread.sleep(4000);
            log(id, info("Sort set to New Posts"));
            return true;
        } catch (Exception e) {
            log(id, warn("Sort dropdown interaction failed: " + e.getMessage()));
            return false;
        }
    }

    private WebElement findSortTrigger() {
        List<WebElement> candidates;
        List<WebElement> currentSortText = driver.findElements(By.xpath(
                "//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'most relevant')" +
                " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'recent activity')" +
                " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new posts')]"))
                .stream().filter(e -> {
                    try { return e.isDisplayed(); } catch (Exception ex) { return false; }
                }).collect(Collectors.toList());
        if (!currentSortText.isEmpty()) {
            for (WebElement el : currentSortText) {
                WebElement clickable = el.findElements(By.xpath("./ancestor-or-self::*[@role='button' or @role='tab' or @role='menuitem' or name()='button']"))
                        .stream().findFirst().orElse(null);
                if (clickable != null) return clickable;
                if (el.getTagName().equals("span") || el.getTagName().equals("div")) return el;
            }
        }

        candidates = driver.findElements(By.xpath(
                "//div[@role='button']//span[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'most')" +
                " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'recent')" +
                " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new')]"));
        if (!candidates.isEmpty()) return candidates.get(0);

        candidates = driver.findElements(By.xpath("//*[contains(@aria-label, 'Sort') or contains(@aria-label, 'sort')]"));
        if (!candidates.isEmpty()) return candidates.get(0);

        candidates = driver.findElements(By.xpath("//div[@role='button' and @aria-haspopup]"));
        for (WebElement c : candidates) {
            try {
                if (c.isDisplayed() && c.getText().toLowerCase(Locale.ROOT).matches(".*(most|recent|sort|new|relevant).*"))
                    return c;
            } catch (Exception ignored) {}
        }

        return null;
    }

    private WebElement findNewPostsOption() {
        List<WebElement> options = driver.findElements(By.xpath(
                "//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new posts') " +
                "and not(ancestor::*[contains(@style,'display:none') or contains(@style,'display: none')])]"));
        if (!options.isEmpty()) return options.get(0);

        options = driver.findElements(By.xpath(
                "//div[@role='menuitem' or @role='option' or @role='menuitemradio']" +
                "[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new posts')]"));
        if (!options.isEmpty()) return options.get(0);

        options = driver.findElements(By.xpath(
                "//span[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new posts')]/.."));
        return options.isEmpty() ? null : options.get(0);
    }

    private boolean isNewPostsSelected() {
        try {
            return !driver.findElements(By.xpath(
                    "//*[(contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'new posts')" +
                    " or contains(@aria-label, 'New posts')" +
                    " or contains(@aria-label, 'new posts'))" +
                    " and (contains(@class, 'selected') or contains(@aria-selected, 'true') or contains(@aria-current, 'true')" +
                    " or ancestor::*[contains(@class, 'selected')] or ancestor::*[@aria-current])]")).isEmpty();
        } catch (Exception e) { return false; }
    }

    private List<PostSnapshot> loadPosts(JavascriptExecutor js, boolean newPostsSort) {
        Map<String, PostSnapshot> posts = new LinkedHashMap<>();
        int unchanged = 0;
        int maxScrolls = newPostsSort ? 8 : 20;
        int targetPosts = newPostsSort ? 12 : 15;
        int unchangedLimit = newPostsSort ? 3 : 4;
        Duration postLoadBudget = newPostsSort ? SORTED_GROUP_POST_LOAD_BUDGET : MANUAL_GROUP_POST_LOAD_BUDGET;
        long startedAt = System.nanoTime();
        RejectionSummary rejectionSummary = new RejectionSummary();

        WebElement feedScroller = findFeedScroller();
        boolean hasFeedScroller = feedScroller != null;

        activity("Scanning group feed for posts | maxScrolls=" + maxScrolls + " | timeBudget=" + postLoadBudget.toSeconds() + "s");

        for (int attempt = 1; attempt <= maxScrolls && posts.size() < targetPosts && withinPostLoadBudget(startedAt, postLoadBudget); attempt++) {
            dismissAlert();
            removeOverlays(js);

            int before = posts.size();
            rememberPosts(posts, findPostSnapshots(), rejectionSummary);
            if (posts.size() >= targetPosts) break;

            if (posts.size() == before) unchanged++;
            else unchanged = 0;
            if (unchanged >= unchangedLimit) break;

            if (hasFeedScroller) {
                js.executeScript("arguments[0].scrollBy(0, Math.max(arguments[0].clientHeight * 0.9, 900));", feedScroller);
            }
            js.executeScript("window.scrollBy(0, Math.max(window.innerHeight * 0.9, 900));");
            sleep(1800 + ThreadLocalRandom.current().nextInt(1200));
        }

        if (posts.size() < 3 && withinPostLoadBudget(startedAt, postLoadBudget)) {
            log(id, warn("Only found " + posts.size() + " posts, trying deeper scroll..."));
            int deepScrolls = newPostsSort ? 3 : 7;
            int deepTarget = newPostsSort ? 8 : 10;
            for (int i = 0; i < deepScrolls && withinPostLoadBudget(startedAt, postLoadBudget); i++) {
                if (hasFeedScroller) {
                    js.executeScript("arguments[0].scrollBy(0, Math.max(arguments[0].clientHeight, 1200));", feedScroller);
                }
                js.executeScript("window.scrollBy(0, Math.max(window.innerHeight, 1200));");
                sleep(2500);
                rememberPosts(posts, findPostSnapshots(), rejectionSummary);
                if (posts.size() >= deepTarget) break;
            }
        }

        if (rejectionSummary.total > 0) {
            activity("Filtered " + rejectionSummary.total + " non-post candidates | " + rejectionSummary.describe());
        }
        if (!withinPostLoadBudget(startedAt, postLoadBudget)) {
            activity("Stopped group feed scan after " + postLoadBudget.toSeconds() + "s time budget");
        }

        return new ArrayList<>(posts.values());
    }

    private boolean withinPostLoadBudget(long startedAt, Duration budget) {
        return Duration.ofNanos(System.nanoTime() - startedAt).compareTo(budget) < 0;
    }

    private void rememberPosts(Map<String, PostSnapshot> posts, List<PostSnapshot> candidates, RejectionSummary rejectionSummary) {
        for (PostSnapshot candidate : candidates) {
            if (!isActionablePostCandidate(candidate)) {
                rejectionSummary.record(candidate.rejectionReasons);
                continue;
            }
            posts.putIfAbsent(candidate.postId, candidate);
        }
    }

    private String postKey(WebElement post) {
        return buildPostSnapshot(post).postId;
    }

    private List<PostSnapshot> findPostSnapshots() {
        List<WebElement> candidates = new ArrayList<>();

        addCandidates(candidates, driver.findElements(By.xpath("//div[@role='feed']/div")));
        addCandidates(candidates, driver.findElements(By.xpath(POST_CONTAINER_XPATH)));
        addCandidates(candidates, driver.findElements(By.xpath("//a[contains(@href,'/posts/') or contains(@href,'/permalink/')]/ancestor::div[@role='article'][1]")));
        addCandidates(candidates, driver.findElements(By.xpath("//a[contains(@href,'/groups/') and contains(@href,'/posts/')]/ancestor::div[@role='article'][1]")));
        addCandidates(candidates, driver.findElements(By.xpath("//*[(contains(@aria-label,'Like') or contains(@aria-label,'Comment') or contains(@aria-label,'Share'))]/ancestor::div[@role='article'][1]")));
        if (candidates.isEmpty()) addCandidates(candidates, driver.findElements(By.xpath("//div[@role='article']")));

        return candidates.stream()
                .map(this::buildPostSnapshot)
                .collect(Collectors.toList());
    }

    private boolean isActionablePostCandidate(PostSnapshot snapshot) {
        return snapshot.score >= 6
                && snapshot.postTime != null
                && snapshot.text != null
                && hasEnoughPostText(snapshot.text)
                && snapshot.likeButton != null
                && (snapshot.commentButton != null || !snapshot.rejectionReasons.contains("comment_control_missing"))
                && !snapshot.rejectionReasons.contains("not_displayed")
                && !snapshot.rejectionReasons.contains("too_small_or_virtualized")
                && !snapshot.rejectionReasons.contains("loading_skeleton")
                && !snapshot.rejectionReasons.contains("inside_comment_surface")
                && !snapshot.rejectionReasons.contains("nested_article_or_comment");
    }

    private void addCandidates(List<WebElement> candidates, List<WebElement> additions) {
        for (WebElement element : additions) {
            if (!candidates.contains(element)) candidates.add(element);
        }
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

    private boolean interactWithPost(PostSnapshot post, int postIndex, JavascriptExecutor js) throws Exception {
        dismissAlert();
        removeOverlays(js);

        WebElement currentPost = post.root;

        if (post.score < 4) {
            log(id, warn("Post #" + postIndex + " is not a valid post: " + String.join(", ", post.rejectionReasons)));
            return false;
        }

        wait.until(ExpectedConditions.visibilityOf(currentPost));
        js.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", currentPost);
        highlightPost(currentPost);
        sleep(ThreadLocalRandom.current().nextInt(2000, 4000));

        String postId = post.postId;
        activity("Checking post #" + postIndex + " | score=" + post.score
                + " | time=" + (post.timestampText == null || post.timestampText.isBlank() ? "unknown" : post.timestampText)
                + " | text=\"" + preview(post.text, 120) + "\"");

        synchronized (processedPosts) {
            if (processedPosts.contains(postId)) {
                activity("Skipping post #" + postIndex + " | already processed");
                log(id, warn("Already processed #" + postIndex + " (dup check)"));
                return false;
            }
        }

        LocalDateTime postTime = post.postTime;
        if (postTime == null || postTime.isBefore(LocalDateTime.now().minus(POST_AGE_LIMIT))) {
            activity("Skipping post #" + postIndex + " | too old or age unknown | time=" + post.timestampText);
            log(id, warn("Post #" + postIndex + " too old or age unknown: " + post.timestampText));
            return false;
        }

        String postText = post.text;
        List<String> keywordMatches = matchedKeywords(postText);
        if (postText.trim().isEmpty() || keywordMatches.isEmpty()) {
            activity("Skipping post #" + postIndex + " | no keyword match");
            log(id, warn("Post #" + postIndex + " no matching keywords"));
            return false;
        }
        activity("Post #" + postIndex + " matched keywords: " + String.join(", ", keywordMatches));

        if (isAlreadyLiked(currentPost)) {
            activity("Skipping post #" + postIndex + " | already liked");
            log(id, warn("Post #" + postIndex + " already liked, skipping"));
            return true;
        }
        activity("Liking post #" + postIndex);
        clickLikeButton(currentPost, post.likeButton, js);
        sleep(ThreadLocalRandom.current().nextInt(1500, 3000));

        activity("Generating reply for post #" + postIndex);
        String reply = generateReply(postText);
        if (reply == null || reply.trim().isEmpty()) {
            activity("No reply generated for post #" + postIndex + " | liked only");
            return true;
        }

        activity("Posting comment on post #" + postIndex + " | reply=\"" + preview(reply, 120) + "\"");
        postComment(currentPost, post.commentButton, reply, js);

        synchronized (processedPosts) { processedPosts.add(postId); }
        totalInteractions.incrementAndGet();

        log(id, ok("Post #" + postIndex + " liked + commented (total: " +
                totalInteractions.get() + "/" + config.maxInteractions + ")"));

        FacebookBot2.logInteraction(postText, reply);
        sleep(ThreadLocalRandom.current().nextInt(4000, 8000));
        return true;
    }

    private boolean isAlreadyLiked(WebElement post) {
        try {
            List<WebElement> unlikeBtns = post.findElements(By.xpath(
                    ".//*[contains(@aria-label,'Unlike') or contains(@aria-label,'unlike')]"));
            unlikeBtns = unlikeBtns.stream()
                    .filter(btn -> isOwnedByPost(btn, post))
                    .filter(btn -> !isInsideCommentSurface(btn))
                    .collect(Collectors.toList());
            if (!unlikeBtns.isEmpty() && unlikeBtns.get(0).isDisplayed()) return true;
            List<WebElement> likedIndicators = post.findElements(By.xpath(
                ".//span[contains(text(),'Liked')] | .//*[contains(@aria-label,'Unlike')]"));
            return likedIndicators.stream()
                    .filter(btn -> isOwnedByPost(btn, post))
                    .filter(btn -> !isInsideCommentSurface(btn))
                    .findAny().isPresent();
        } catch (Exception e) { return false; }
    }

    private void clickLikeButton(WebElement post, WebElement preferredButton, JavascriptExecutor js) throws Exception {
        try {
            dismissAlert();
            WebElement btn = preferredButton != null && isUsableControl(preferredButton) ? preferredButton : findLikeButton(post);
            if (btn == null) throw new Exception("Like button not found");
            js.executeScript("arguments[0].scrollIntoView({block: 'center'});", btn);
            sleep(500);
            js.executeScript("arguments[0].click();", btn);
        } catch (Exception e) {
            throw new Exception("Failed to like: " + e.getMessage());
        }
    }

    private void postComment(WebElement post, WebElement preferredButton, String comment, JavascriptExecutor js) throws Exception {
        dismissAlert();
        removeOverlays(js);

        WebElement btn = preferredButton != null && isUsableControl(preferredButton) ? preferredButton : findCommentButton(post);
        if (btn != null) {
            js.executeScript("arguments[0].click();", btn);
            sleep(800);
        }

        WebElement textArea = wait.until(wd -> post.findElements(By.xpath(".//div[@role='textbox' and @contenteditable='true']"))
                .stream()
                .filter(box -> isOwnedByPost(box, post))
                .findFirst()
                .orElse(null));

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
            if (isOnTwoFactorPage()) return false;

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

            if (isOnDeviceApprovalPage()) return false;

            // A genuine captcha challenge may still expose only a captcha URL.
            String url = driver.getCurrentUrl().toLowerCase(Locale.ROOT);
            if (url.contains("captcha")) return true;
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

    private void waitForLoginSecurityChallenges() {
        waitForLoginChallengeToLoad();

        if (isOnTwoFactorPage()) {
            waitForTwoFactorResolution();
        }

        if (isOnDeviceApprovalPage()) {
            waitForDeviceApprovalResolution();
        }
        waitForCheckpointFollowUpResolution();

        waitForCaptchaResolution();

        // Facebook can show these in sequence, so check again after the first challenge clears.
        if (isOnTwoFactorPage()) {
            waitForTwoFactorResolution();
        }
        if (isOnDeviceApprovalPage()) {
            waitForDeviceApprovalResolution();
        }
        waitForCheckpointFollowUpResolution();
        waitForCaptchaResolution();
    }

    private void waitForLoginChallengeToLoad() {
        int waited = 0;
        while (waited < 30) {
            try {
                waitForPageLoad();
                if (isLoggedInMarkerPresent() || isOnTwoFactorPage() || isOnDeviceApprovalPage()
                        || isActiveCaptchaPresent() || isLoginErrorPresent()) {
                    return;
                }
                String url = driver.getCurrentUrl().toLowerCase(Locale.ROOT);
                if (!url.contains("/login")) return;
                Thread.sleep(1000);
                waited++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                sleep(1000);
                waited++;
            }
        }
    }

    private void waitForTwoFactorResolution() {
        log(id, warn("2FA page detected. Complete it in the browser, the bot will resume automatically."));
        log(id, info("Waiting for 2FA to be completed..."));

        int waited = 0;
        while (isOnTwoFactorPage() && waited < 180) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            waited += 2;
            dismissAlert();
        }

        if (isOnTwoFactorPage()) {
            log(id, fail("2FA not completed after 3 minutes. Continuing anyway..."));
        } else {
            log(id, ok("2FA completed. Continuing..."));
            sleep(2000);
        }
    }

    private void waitForDeviceApprovalResolution() {
        log(id, warn("Facebook device approval detected. Approve the login from your other device."));
        log(id, info("Waiting for Facebook to confirm this login..."));

        int waited = 0;
        while (isOnDeviceApprovalPage() && waited < 900) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            waited += 2;
            dismissAlert();
        }

        if (isOnDeviceApprovalPage()) {
            log(id, fail("Device approval was not completed after 15 minutes. Continuing anyway..."));
        } else {
            log(id, ok("Device approval completed. Continuing..."));
            sleep(2000);
        }
    }

    private void waitForCheckpointFollowUpResolution() {
        int waited = 0;
        boolean announced = false;
        while (isOnCheckpointFollowUpPage() && waited < 180) {
            if (!announced) {
                log(id, info("Completing Facebook checkpoint follow-up..."));
                announced = true;
            }

            clickCheckpointContinueButton();
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            waited += 2;
            dismissAlert();
        }

        if (announced && isOnCheckpointFollowUpPage()) {
            log(id, warn("Still on Facebook checkpoint follow-up after 3 minutes."));
        }
    }

    private boolean isLoggedInMarkerPresent() {
        try {
            return !driver.findElements(By.xpath("//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), \"what's on your mind\")"
                    + " or contains(@placeholder, 'post')"
                    + " or contains(@aria-label, 'Create a post')"
                    + " or contains(@data-pagelet, 'Feed')"
                    + " or contains(@id, 'mbasic_logout')"
                    + " or contains(@href, '/logout')]")).isEmpty();
        } catch (Exception e) { return false; }
    }

    private boolean isLoginErrorPresent() {
        try {
            return !driver.findElements(By.xpath("//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'incorrect')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'wrong')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'try again')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'couldn')]")).isEmpty();
        } catch (Exception e) { return false; }
    }

    private boolean isOnDeviceApprovalPage() {
        try {
            if (isLoggedInMarkerPresent() || isOnTwoFactorPage()) return false;
            String url = driver.getCurrentUrl().toLowerCase(Locale.ROOT);
            boolean checkpointUrl = url.contains("/checkpoint/") || url.contains("login_checkpoint")
                    || url.contains("checkpoint/?next") || url.contains("two_factor/remember_browser");

            boolean approvalText = !driver.findElements(By.xpath(
                    "//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'verify it')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'verify that')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'is this you')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'confirm your identity')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'approve')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'another device')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'notification')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'login attempt')"
                    + " or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'check your notifications')]")).isEmpty();

            return checkpointUrl && approvalText;
        } catch (Exception e) { return false; }
    }

    private boolean isOnCheckpointFollowUpPage() {
        try {
            if (isLoggedInMarkerPresent() || isOnTwoFactorPage() || isOnDeviceApprovalPage() || isActiveCaptchaPresent()) {
                return false;
            }
            String url = driver.getCurrentUrl().toLowerCase(Locale.ROOT);
            return url.contains("/checkpoint/") || url.contains("login_checkpoint")
                    || url.contains("two_factor/remember_browser") || url.contains("/recover/initiate/");
        } catch (Exception e) { return false; }
    }

    private void clickCheckpointContinueButton() {
        try {
            List<WebElement> buttons = driver.findElements(By.xpath(
                    "//button[not(@disabled)] | //div[@role='button'] | //a[@role='button'] | //input[@type='submit' or @type='button']"));
            for (WebElement button : buttons) {
                String text = (button.getText() + " " + button.getAttribute("value") + " " + button.getAttribute("aria-label"))
                        .toLowerCase(Locale.ROOT);
                if (!button.isDisplayed()) continue;
                if (text.matches(".*(continue|next|ok|done|save|trust|remember|this was me|yes).*")) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", button);
                    sleep(1000);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private PostSnapshot buildPostSnapshot(WebElement root) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        try {
            if (root == null || !root.isDisplayed()) reasons.add("not_displayed");
            else score++;

            if (elementHeight(root) < 80) reasons.add("too_small_or_virtualized");
            else score++;

            if (isLoadingSkeleton(root)) reasons.add("loading_skeleton");
            else score++;

            if (isInsideCommentSurface(root)) reasons.add("inside_comment_surface");
            if (!isTopLevelCandidate(root)) reasons.add("nested_article_or_comment");

            String text = cleanPostText(root);
            if (!hasEnoughPostText(text)) reasons.add("post_text_too_short");
            else score++;

            String timestampText = extractTimestampText(root);
            LocalDateTime postTime = parseFacebookTime(timestampText);
            if (postTime == null) reasons.add("timestamp_missing_or_unparsed");
            else score += 2;

            WebElement likeButton = findLikeButton(root);
            WebElement commentButton = findCommentButton(root);
            if (likeButton == null) reasons.add("like_control_missing");
            else score++;
            if (commentButton == null && findCommentComposer(root) == null) reasons.add("comment_control_missing");
            else score++;

            String postId = extractPostId(root);
            if (postId == null || postId.isBlank()) postId = fallbackPostId(root, text, timestampText);
            return new PostSnapshot(root, likeButton, commentButton, postId, text, timestampText, postTime, score, reasons);
        } catch (StaleElementReferenceException e) {
            reasons.add("stale_element");
            return new PostSnapshot(root, null, null, "stale_" + System.nanoTime(), "", "", null, 0, reasons);
        } catch (Exception e) {
            reasons.add("snapshot_error:" + e.getClass().getSimpleName());
            return new PostSnapshot(root, null, null, "error_" + System.nanoTime(), "", "", null, 0, reasons);
        }
    }

    private WebElement findLikeButton(WebElement post) {
        return findFirstUsable(post, ".//*[(contains(@aria-label,'Like') or contains(@aria-label,'like')) " +
                "and not(contains(@aria-label,'Unlike') or contains(@aria-label,'unlike'))] " +
                "| .//span[normalize-space()='Like']/ancestor::*[@role='button' or name()='button' or name()='div'][1]");
    }

    private WebElement findCommentButton(WebElement post) {
        WebElement button = findFirstUsable(post, ".//*[(contains(@aria-label,'Comment') or contains(@aria-label,'comment')) " +
                "and not(contains(@aria-label,'Uncomment') or contains(@aria-label,'uncomment'))] " +
                "| .//span[normalize-space()='Comment']/ancestor::*[@role='button' or name()='button' or name()='div'][1]");
        if (button != null) return button;
        return findFirstUsable(post, ".//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'answer as') " +
                "or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'comment as')]");
    }

    private WebElement findCommentComposer(WebElement post) {
        return findFirstUsable(post, ".//div[@role='textbox' and @contenteditable='true']");
    }

    private WebElement findFirstUsable(WebElement root, String xpath) {
        try {
            for (WebElement element : root.findElements(By.xpath(xpath))) {
                if (isUsableControl(element) && isOwnedByPost(element, root) && !isInsideCommentSurface(element)) return element;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isUsableControl(WebElement element) {
        try { return element != null && element.isDisplayed() && element.isEnabled() && elementHeight(element) >= 1; }
        catch (Exception e) { return false; }
    }

    private int elementHeight(WebElement element) {
        try { return element.getRect().getHeight(); }
        catch (Exception e) { return 0; }
    }

    private boolean isLoadingSkeleton(WebElement element) {
        try {
            String html = ((String) ((JavascriptExecutor) driver).executeScript("return arguments[0].outerHTML.substring(0, 2500);", element)).toLowerCase(Locale.ROOT);
            return html.contains("loading-state") || html.contains("aria-label=\"loading") || html.contains("suspended-feed");
        } catch (Exception e) { return false; }
    }

    private boolean isTopLevelCandidate(WebElement element) {
        try {
            Object parentArticle = ((JavascriptExecutor) driver).executeScript(
                    "return arguments[0].parentElement ? arguments[0].parentElement.closest('div[role=\"article\"]') : null;",
                    element);
            if (parentArticle == null) return true;
            String ariaLabel = ((WebElement) parentArticle).getAttribute("aria-label");
            return ariaLabel == null || !ariaLabel.toLowerCase(Locale.ROOT).startsWith("comment by ");
        } catch (Exception e) { return true; }
    }

    private String extractPostId(WebElement post) {
        try {
            String id = post.getAttribute("id");
            if (id != null && id.startsWith("hyperfeed_story_")) return id;

            List<WebElement> links = post.findElements(By.xpath(".//a[contains(@href,'/posts/') or contains(@href,'/permalink/')]"));
            for (WebElement link : links) {
                Matcher m = POST_URL_PATTERN.matcher(link.getAttribute("href"));
                if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String fallbackPostId(WebElement post, String text, String timestampText) {
        String author = "";
        try {
            List<String> lines = Arrays.stream(post.getText().split("\\R"))
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
            if (!lines.isEmpty()) author = lines.get(0);
        } catch (Exception ignored) {}
        return "hash_" + Math.abs((author + "|" + timestampText + "|" + text).hashCode());
    }

    private LocalDateTime getPostTime(WebElement post) {
        return parseFacebookTime(extractTimestampText(post));
    }

    private LocalDateTime parseFacebookTime(String t) {
        if (t == null || t.isBlank()) return null;
        String value = t.replace("\u00b7", " ").replaceAll("\\s+", " ").trim();
        String lower = value.toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();

        if (lower.matches("^(just now|now)$")) return now;
        Matcher relative = RELATIVE_TIME_PATTERN.matcher(lower);
        if (relative.matches()) {
            int amount = Integer.parseInt(relative.group(1));
            String unit = relative.group(2).toLowerCase(Locale.ROOT);
            if (unit.startsWith("m")) return now.minusMinutes(amount);
            if (unit.startsWith("h")) return now.minusHours(amount);
            if (unit.startsWith("d")) return now.minusDays(amount);
            if (unit.startsWith("w")) return now.minusWeeks(amount);
        }
        if (lower.equals("yesterday")) return now.minusDays(1);
        if (lower.startsWith("yesterday at ")) return parseTodayOrYesterday(value, false);
        if (lower.startsWith("today at ")) return parseTodayOrYesterday(value, true);

        Matcher absolute = ABSOLUTE_TIME_PATTERN.matcher(value);
        if (absolute.find()) return parseAbsoluteFacebookDate(absolute.group());
        if (SHORT_ABSOLUTE_TIME_PATTERN.matcher(value).matches()) return parseTodayOrYesterday(value, lower.startsWith("today"));
        return null;
    }

    private String cleanPostText(WebElement post) {
        try {
            List<WebElement> els = post.findElements(By.xpath(
                    ".//div[@data-ad-preview='message'] | .//div[contains(@data-ad-preview,'message')]"));
            String text = els.stream().map(WebElement::getText).filter(t -> t.length() > 20)
                    .collect(Collectors.joining("\n"))
                    .replaceAll("\\s+", " ").trim();
            if (!text.isBlank()) return stripLeadingObfuscatedText(text);

            List<String> lines = Arrays.stream(post.getText().split("\\R"))
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
            int commentSectionIndex = firstCommentSectionLineIndex(lines);
            if (commentSectionIndex >= 0) lines = new ArrayList<>(lines.subList(0, commentSectionIndex));
            int timestampIndex = firstTimestampLineIndex(lines);
            int start = timestampIndex >= 0 ? timestampIndex + 1 : Math.min(1, lines.size());
            List<String> content = new ArrayList<>();
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isPostTextTerminator(line)) break;
                if (isPostMetadataLine(line)) continue;
                line = stripLeadingObfuscatedText(line);
                if (!line.isBlank()) content.add(line);
            }
            return stripLeadingObfuscatedText(String.join(" ", content).replaceAll("\\s+", " ").trim());
        } catch (Exception e) { return ""; }
    }

    private String extractTimestampText(WebElement post) {
        try {
            List<String> lines = Arrays.stream(post.getText().split("\\R"))
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
            int commentSectionIndex = firstCommentSectionLineIndex(lines);
            if (commentSectionIndex >= 0) lines = new ArrayList<>(lines.subList(0, commentSectionIndex));
            String lineTimestamp = firstTimestampText(lines.toArray(new String[0]));
            if (!lineTimestamp.isBlank()) return lineTimestamp;

            for (WebElement el : post.findElements(By.xpath(".//abbr | .//*[@aria-label] | .//a[@href] | .//span"))) {
                String candidate = firstTimestampText(
                        attr(el, "aria-label"), attr(el, "title"), attr(el, "aria-label"), safeText(el));
                if (!candidate.isBlank()) return candidate;
            }
            return "";
        } catch (Exception e) { return ""; }
    }

    private String firstTimestampText(String... values) {
        for (String value : values) {
            if (value == null) continue;
            for (String piece : value.split("[\\r\\n·•]") ) {
                String cleaned = piece.replaceAll("\\s+", " ").trim();
                if (isTimestampLike(cleaned)) return cleaned;
            }
        }
        return "";
    }

    private int firstTimestampLineIndex(List<String> lines) {
        for (int i = 0; i < Math.min(lines.size(), 8); i++) {
            if (isTimestampLike(lines.get(i))) return i;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (isTimestampLike(lines.get(i))) return i;
        }
        return -1;
    }

    private boolean isTimestampLike(String text) {
        if (text == null) return false;
        String value = text.replace("\u00b7", " ").replaceAll("\\s+", " ").trim();
        if (value.isBlank() || value.length() > 80) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.matches("^(just now|now)$")
                || RELATIVE_TIME_PATTERN.matcher(lower).matches()
                || lower.equals("yesterday")
                || lower.startsWith("yesterday at ")
                || lower.startsWith("today at ")
                || ABSOLUTE_TIME_PATTERN.matcher(value).find();
    }

    private LocalDateTime parseTodayOrYesterday(String value, boolean today) {
        try {
            String timeText = value.replaceFirst("(?i)^(today|yesterday)\\s+at\\s+", "").trim().toUpperCase(Locale.ROOT);
            LocalTime time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern("h:mm a", Locale.US));
            LocalDate date = today ? LocalDate.now() : LocalDate.now().minusDays(1);
            return LocalDateTime.of(date, time);
        } catch (DateTimeParseException e) { return null; }
    }

    private LocalDateTime parseAbsoluteFacebookDate(String value) {
        String cleaned = value.replaceAll("\\s+", " ").trim();
        boolean hasYear = cleaned.matches(".*\\b\\d{4}\\b.*");
        boolean hasTime = cleaned.matches(".*\\d{1,2}:\\d{2}.*");
        List<String> patterns = new ArrayList<>();
        String parseValue = cleaned;

        if (hasTime && hasYear) {
            patterns.add("MMMM d, yyyy 'at' h:mm a");
            patterns.add("MMM d, yyyy 'at' h:mm a");
        } else if (hasTime) {
            parseValue = cleaned + ", " + LocalDate.now().getYear();
            patterns.add("MMMM d 'at' h:mm a, yyyy");
            patterns.add("MMM d 'at' h:mm a, yyyy");
        } else if (hasYear) {
            patterns.add("MMMM d, yyyy");
            patterns.add("MMM d, yyyy");
        } else {
            parseValue = cleaned + ", " + LocalDate.now().getYear();
            patterns.add("MMMM d, yyyy");
            patterns.add("MMM d, yyyy");
        }

        for (String pattern : patterns) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.US);
                if (hasTime) return LocalDateTime.parse(parseValue, formatter);
                return LocalDateTime.of(LocalDate.parse(parseValue, formatter), LocalTime.MIDNIGHT);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean isPostTextTerminator(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("view more")
                || lower.startsWith("view all")
                || lower.startsWith("most relevant")
                || lower.startsWith("comment as")
                || lower.startsWith("answer as")
                || lower.matches(".*\\blike\\s+reply\\s+share\\b.*")
                || lower.matches(".*\\blike\\s+comment\\s+share\\b.*");
    }

    private int firstCommentSectionLineIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            if (lower.startsWith("view more comments")
                    || lower.startsWith("view more answers")
                    || lower.startsWith("view previous comments")
                    || lower.startsWith("view previous answers")
                    || lower.startsWith("view all comments")
                    || lower.startsWith("view all answers")
                    || lower.startsWith("most relevant")
                    || lower.startsWith("comment as ")
                    || lower.startsWith("answer as ")) {
                return i;
            }
        }
        return -1;
    }

    private boolean isPostMetadataLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return isTimestampLike(line)
                || lower.equals("follow")
                || lower.equals("top contributor")
                || lower.equals("rising contributor")
                || lower.equals("admin")
                || lower.equals("author")
                || lower.matches("^\\d+$")
                || lower.matches("^[\\d.,k]+\\s*(comments?|answers?|shares?)$")
                || lower.matches("^(like|comment|share|reply|send)$");
    }

    private String stripLeadingObfuscatedText(String line) {
        String value = line == null ? "" : line.trim();
        if (value.isBlank()) return "";

        String[] pieces = value.split("[\u00b7•]");
        for (int i = pieces.length - 1; i >= 0; i--) {
            String piece = pieces[i].replaceAll("\\s+", " ").trim();
            if (piece.length() >= 15 && !isMostlyObfuscatedText(piece)) return piece;
        }

        value = value.replaceFirst("^(?i)(?:[a-z]\\s+){8,}", "").trim();
        return isMostlyObfuscatedText(value) ? "" : value;
    }

    private boolean isMostlyObfuscatedText(String text) {
        if (text == null || text.isBlank()) return true;
        String[] tokens = text.trim().split("\\s+");
        if (tokens.length < 8) return false;

        long singleLetterTokens = Arrays.stream(tokens)
                .filter(token -> token.matches("(?i)[a-z]")).count();
        long wordTokens = Arrays.stream(tokens)
                .filter(token -> token.matches("(?i)[a-z]{3,}.*")).count();
        return singleLetterTokens >= 8 && singleLetterTokens > wordTokens * 2;
    }

    private String attr(WebElement element, String name) {
        try {
            String value = element.getAttribute(name);
            return value == null ? "" : value;
        } catch (Exception e) { return ""; }
    }

    private String safeText(WebElement element) {
        try {
            String value = element.getText();
            return value == null ? "" : value;
        } catch (Exception e) { return ""; }
    }

    private boolean isActualPost(WebElement element) {
        try {
            if (!element.isDisplayed() || !isTopLevelArticle(element) || isInsideCommentSurface(element)) return false;

            String ariaLabel = element.getAttribute("aria-label");
            if (ariaLabel != null && ariaLabel.toLowerCase(Locale.ROOT).startsWith("comment by ")) return false;

            String dp = element.getAttribute("data-pagelet");
            if (dp != null && !dp.isBlank()) {
                String lowerDp = dp.toLowerCase(Locale.ROOT);
                if (lowerDp.contains("comment") || lowerDp.contains("reply")) return false;
            }
        } catch (Exception ignored) {}
        try {
            if (!element.findElements(By.xpath(".//a[contains(@href,'/posts/') or contains(@href,'/permalink/')]")).isEmpty()
                    && !element.findElements(By.xpath(".//div[@data-ad-preview='message'] | .//div[contains(@data-ad-preview,'message')]")).isEmpty()) {
                return true;
            }
        } catch (Exception ignored) {}
        try {
            List<WebElement> actionButtons = element.findElements(By.xpath(
                    ".//*[(contains(@aria-label,'Like') or contains(@aria-label,'like') " +
                    "or contains(@aria-label,'Comment') or contains(@aria-label,'comment') " +
                    "or contains(@aria-label,'Share') or contains(@aria-label,'share'))]"));
            long ownedActions = actionButtons.stream()
                    .filter(btn -> isOwnedByPost(btn, element))
                    .filter(btn -> !isInsideCommentSurface(btn))
                    .count();
            return ownedActions >= 2 && !cleanPostText(element).isBlank();
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isTopLevelArticle(WebElement element) {
        try {
            Object closestParentArticle = ((JavascriptExecutor) driver).executeScript(
                    "return arguments[0].parentElement ? arguments[0].parentElement.closest('div[role=\"article\"]') : null;",
                    element);
            return closestParentArticle == null;
        } catch (Exception e) { return false; }
    }

    private boolean isOwnedByPost(WebElement element, WebElement post) {
        try {
            Object closestArticle = ((JavascriptExecutor) driver).executeScript(
                    "return arguments[0].closest('div[role=\"article\"]');", element);
            if (closestArticle instanceof WebElement article && !post.equals(article)) {
                String ariaLabel = article.getAttribute("aria-label");
                if (ariaLabel != null && ariaLabel.toLowerCase(Locale.ROOT).startsWith("comment by ")) {
                    return false;
                }
            }
            Object contained = ((JavascriptExecutor) driver).executeScript(
                    "return arguments[1].contains(arguments[0]);", element, post);
            return Boolean.TRUE.equals(contained);
        } catch (Exception e) { return false; }
    }

    private boolean isInsideCommentSurface(WebElement element) {
        try {
            Object commentAncestor = ((JavascriptExecutor) driver).executeScript(
                    "return arguments[0].parentElement ? arguments[0].parentElement.closest(" +
                    "'[aria-label*=\"Comment\"], [aria-label*=\"comment\"], " +
                    "[data-testid*=\"comment\"], [class*=\"comment\"], [id*=\"comment\"]') : null;",
                    element);
            return commentAncestor != null;
        } catch (Exception e) { return false; }
    }

    private void humanType(WebElement element, String text) {
        for (char c : text.toCharArray()) {
            element.sendKeys(String.valueOf(c));
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(60, 180));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                element.sendKeys(text.substring(text.indexOf(c)));
                return;
            }
        }
    }

    private boolean isOnTwoFactorPage() {
        try {
            String url = driver.getCurrentUrl().toLowerCase(Locale.ROOT);
            if (url.contains("two_step_verification") || url.contains("approvals_code")) return true;
            return !driver.findElements(By.xpath("//input[contains(@name,'approvals_code') or contains(@id,'approvals_code')]"
                    + " | //input[contains(@autocomplete,'one-time-code')]"
                    + " | //*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'two-factor')]"
                    + " | //*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'two factor')]"
                    + " | //*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'authentication code')]"
                    + " | //*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'login code')]")).isEmpty();
        } catch (Exception e) { return false; }
    }

    private void waitForUrlChange(String excludeFragment, int timeoutSec) throws Exception {
        for (int i = 0; i < timeoutSec; i++) {
            Thread.sleep(1000);
            try {
                if (!driver.getCurrentUrl().contains(excludeFragment)) return;
            } catch (Exception e) { return; }
        }
        log(id, warn("Timed out waiting for URL to leave: " + excludeFragment));
    }

    private boolean containsKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return Arrays.stream(KEYWORDS).anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
    }

    private boolean hasEnoughPostText(String text) {
        if (text == null) return false;
        String value = text.replaceAll("\\s+", " ").trim();
        if (value.length() >= 20) return true;
        return isShortButMeaningfulPost(value);
    }

    private boolean isShortButMeaningfulPost(String text) {
        if (text == null) return false;
        String value = text.replaceAll("\\s+", " ").trim();
        if (value.length() < 4 || value.length() >= 20) return false;
        if (isPostMetadataLine(value) || isPostTextTerminator(value)) return false;

        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("?")
                || lower.contains("help")
                || lower.contains("advice")
                || lower.contains("pain")
                || lower.contains("symptoms");
    }

    private List<String> matchedKeywords(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        String lower = text.toLowerCase(Locale.ROOT);
        return Arrays.stream(KEYWORDS)
                .filter(keyword -> lower.contains(keyword.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }

    private void activity(String message) {
        log(id, info("Activity: " + message));
    }

    private String preview(String text, int maxLength) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        if (value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
