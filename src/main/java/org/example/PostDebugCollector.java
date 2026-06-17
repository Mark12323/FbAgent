package org.example;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openqa.selenium.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.example.FacebookBot2.*;

public class PostDebugCollector {
    private static final int MAX_CANDIDATES_PER_GROUP = 40;
    private static final int MAX_TEXT_LENGTH = 5000;
    private static final int MAX_HTML_LENGTH = 7000;
    private static final Pattern RELATIVE_TIME_PATTERN = Pattern.compile("(?i)^(?:about\\s+)?(\\d+)\\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks)\\b.*");
    private static final Pattern ABSOLUTE_TIME_PATTERN = Pattern.compile("(?i)\\b(?:jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december)\\s+\\d{1,2}(?:,\\s*\\d{4})?(?:\\s+at\\s+\\d{1,2}:\\d{2}\\s*(?:am|pm))?\\b");
    private static final Pattern SHORT_ABSOLUTE_TIME_PATTERN = Pattern.compile("(?i)^(today|yesterday)\\s+at\\s+\\d{1,2}:\\d{2}\\s*(?:am|pm)$");

    private final WebDriver driver;
    private final JavascriptExecutor js;
    private final int workerId;
    private final Path runDir;
    private final Path screenshotsDir;
    private final Path groupsFile;
    private final Path candidatesFile;
    private final Path errorsFile;
    private final Map<String, Integer> selectorCounts = new LinkedHashMap<>();

    public PostDebugCollector(WebDriver driver, int workerId) throws IOException, JSONException {
        this.driver = driver;
        this.js = (JavascriptExecutor) driver;
        this.workerId = workerId;

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
        this.runDir = Path.of("debug_runs", "facebook_post_debug_" + stamp + "_w" + workerId);
        this.screenshotsDir = runDir.resolve("screenshots");
        this.groupsFile = runDir.resolve("groups.jsonl");
        this.candidatesFile = runDir.resolve("post_candidates.jsonl");
        this.errorsFile = runDir.resolve("errors.jsonl");

        Files.createDirectories(screenshotsDir);
        writeJson(runDir.resolve("debug_manifest.json"), buildManifest());
    }

    public void capture(List<String> groupUrls, int groupLimit) throws JSONException {
        int limit = Math.min(Math.max(groupLimit, 1), groupUrls.size());
        log(workerId, info("Debug post detection enabled. Capturing " + limit + " groups into " + runDir));

        for (int i = 0; i < limit; i++) {
            captureGroup(i + 1, groupUrls.get(i));
        }

        writeJson(runDir.resolve("selector_summary.json"), new JSONObject()
                .put("runDir", runDir.toString())
                .put("selectorCounts", new JSONObject(selectorCounts))
                .put("finishedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        log(workerId, ok("Debug capture complete: " + runDir));
    }

    private void captureGroup(int groupIndex, String groupUrl) {
        try {
            driver.get(groupUrl);
            waitForPageLoad();
            sleep(2500);
            scrollFeed();

            String pageShot = screenshotPage("group_" + groupIndex + "_viewport.png");
            JSONObject group = browserContext()
                    .put("groupIndex", groupIndex)
                    .put("inputGroupUrl", groupUrl)
                    .put("currentUrl", safeUrl())
                    .put("title", safeTitle())
                    .put("viewportScreenshot", pageShot)
                    .put("capturedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            Map<WebElement, Set<String>> candidates = findCandidates();
            group.put("candidateCount", candidates.size());
            appendJsonLine(groupsFile, group);

            int candidateIndex = 0;
            for (Map.Entry<WebElement, Set<String>> entry : candidates.entrySet()) {
                if (++candidateIndex > MAX_CANDIDATES_PER_GROUP) break;
                captureCandidate(groupIndex, candidateIndex, entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            writeError("group", groupIndex, groupUrl, e);
        }
    }

    private Map<WebElement, Set<String>> findCandidates() {
        Map<WebElement, Set<String>> candidates = new LinkedHashMap<>();
        addCandidates(candidates, "feed_direct_children", By.xpath("//div[@role='feed']/div"));
        addCandidates(candidates, "role_article", By.xpath("//div[@role='article']"));
        addCandidates(candidates, "data_pagelet_story", By.xpath("//div[contains(@data-pagelet,'FeedUnit') or contains(@data-pagelet,'Story')]"));
        addCandidates(candidates, "post_link_article", By.xpath("//a[contains(@href,'/posts/') or contains(@href,'/permalink/')]/ancestor::div[@role='article'][1]"));
        addCandidates(candidates, "group_post_link_article", By.xpath("//a[contains(@href,'/groups/') and contains(@href,'/posts/')]/ancestor::div[@role='article'][1]"));
        addCandidates(candidates, "action_button_article", By.xpath("//*[(contains(@aria-label,'Like') or contains(@aria-label,'Comment') or contains(@aria-label,'Share'))]/ancestor::div[@role='article'][1]"));
        addCandidates(candidates, "timestamp_link_article", By.xpath("//a[.//span and (contains(@href,'/posts/') or contains(@href,'/permalink/') or contains(@href,'/groups/'))]/ancestor::div[@role='article'][1]"));
        return candidates;
    }

    private void addCandidates(Map<WebElement, Set<String>> candidates, String strategy, By by) {
        try {
            List<WebElement> found = driver.findElements(by);
            selectorCounts.merge(strategy, found.size(), Integer::sum);
            for (WebElement element : found) {
                if (element == null) continue;
                candidates.computeIfAbsent(element, ignored -> new LinkedHashSet<>()).add(strategy);
            }
        } catch (Exception e) {
            writeError("selector", 0, strategy, e);
        }
    }

    private void captureCandidate(int groupIndex, int candidateIndex, WebElement element, Set<String> strategies) {
        try {
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", element);
            sleep(700);

            String rawText = safeElementText(element);
            String cleanText = cleanPostText(element);
            JSONArray matchedKeywords = matchedKeywords(cleanText.isBlank() ? rawText : cleanText);
            JSONObject timestamps = timestampData(element);
            JSONObject actions = actionData(element);
            JSONObject links = linkData(element);
            JSONObject attrs = attributes(element);
            JSONObject rect = rect(element);
            JSONArray rejectionReasons = rejectionReasons(element, cleanText, timestamps, actions);
            int score = snapshotScore(element, cleanText, timestamps, actions, rejectionReasons);
            boolean accepted = acceptedByDebugHeuristic(score, cleanText, timestamps, actions, rejectionReasons);

            JSONObject record = new JSONObject()
                    .put("groupIndex", groupIndex)
                    .put("candidateIndex", candidateIndex)
                    .put("currentUrl", safeUrl())
                    .put("strategies", new JSONArray(strategies))
                    .put("acceptedByDebugHeuristic", accepted)
                    .put("debugScore", score)
                    .put("rejectionReasons", rejectionReasons)
                    .put("keywordMatches", matchedKeywords)
                    .put("textPreview", truncate((cleanText.isBlank() ? rawText : cleanText).replaceAll("\\s+", " "), 500))
                    .put("cleanText", truncate(cleanText, MAX_TEXT_LENGTH))
                    .put("rawText", truncate(rawText, MAX_TEXT_LENGTH))
                    .put("timestamps", timestamps)
                    .put("actions", actions)
                    .put("links", links)
                    .put("attributes", attrs)
                    .put("rect", rect)
                    .put("outerHtml", truncate(outerHtml(element), MAX_HTML_LENGTH))
                    .put("screenshot", screenshotElement(element, "group_" + groupIndex + "_candidate_" + candidateIndex + ".png"))
                    .put("capturedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            appendJsonLine(candidatesFile, record);
        } catch (Exception e) {
            writeError("candidate", candidateIndex, "group=" + groupIndex, e);
        }
    }

    private JSONArray rejectionReasons(WebElement element, String cleanText, JSONObject timestamps, JSONObject actions) throws JSONException {
        JSONArray reasons = new JSONArray();
        if (!safeDisplayed(element)) reasons.put("not_displayed");
        if (elementHeight(element) < 80) reasons.put("too_small_or_virtualized");
        if (isLoadingSkeleton(element)) reasons.put("loading_skeleton");
        if (!hasEnoughPostText(cleanText)) reasons.put("post_text_too_short");
        if (!timestamps.optBoolean("hasParsedTimestamp")) reasons.put("timestamp_missing_or_unparsed");
        if (!actions.optBoolean("hasLike")) reasons.put("missing_like_control");
        if (!actions.optBoolean("hasComment") && !actions.optBoolean("hasCommentComposer")) reasons.put("missing_comment_control");
        if (isNestedArticle(element)) reasons.put("nested_article_or_comment");
        if (isCommentSurface(element)) reasons.put("inside_comment_surface");
        return reasons;
    }

    private int snapshotScore(WebElement element, String cleanText, JSONObject timestamps, JSONObject actions, JSONArray rejectionReasons) {
        int score = 0;
        if (safeDisplayed(element)) score++;
        if (elementHeight(element) >= 80) score++;
        if (!isLoadingSkeleton(element)) score++;
        if (hasEnoughPostText(cleanText)) score++;
        if (timestamps.optBoolean("hasParsedTimestamp")) score += 2;
        if (actions.optBoolean("hasLike")) score++;
        if (actions.optBoolean("hasComment") || actions.optBoolean("hasCommentComposer")) score++;
        return score;
    }

    private boolean acceptedByDebugHeuristic(int score, String cleanText, JSONObject timestamps, JSONObject actions, JSONArray reasons) {
        Set<String> disqualifiers = new HashSet<>();
        for (int i = 0; i < reasons.length(); i++) disqualifiers.add(reasons.optString(i));
        return score >= 6
                && timestamps.optBoolean("hasParsedTimestamp")
                && hasEnoughPostText(cleanText)
                && actions.optBoolean("hasLike")
                && (actions.optBoolean("hasComment") || actions.optBoolean("hasCommentComposer"))
                && !disqualifiers.contains("not_displayed")
                && !disqualifiers.contains("too_small_or_virtualized")
                && !disqualifiers.contains("loading_skeleton")
                && !disqualifiers.contains("inside_comment_surface")
                && !disqualifiers.contains("nested_article_or_comment");
    }

    private JSONObject timestampData(WebElement element) throws JSONException {
        JSONArray items = new JSONArray();
        String selectedText = "";
        LocalDateTime parsed = null;
        try {
            List<String> lines = visibleLines(element);
            int commentSectionIndex = firstCommentSectionLineIndex(lines);
            if (commentSectionIndex >= 0) lines = new ArrayList<>(lines.subList(0, commentSectionIndex));
            selectedText = firstTimestampText(lines.toArray(new String[0]));
            parsed = parseFacebookTime(selectedText);

            List<WebElement> els = element.findElements(By.xpath(".//abbr | .//*[@aria-label] | .//a[@href] | .//span"));
            for (WebElement el : els) {
                String text = safeElementText(el);
                String title = safeAttr(el, "title");
                String aria = safeAttr(el, "aria-label");
                String href = safeAttr(el, "href");
                String candidate = firstTimestampText(aria, title, text);
                LocalDateTime candidateParsed = parseFacebookTime(candidate);
                if (parsed == null && candidateParsed != null) {
                    selectedText = candidate;
                    parsed = candidateParsed;
                }
                if (items.length() < 10 && (!text.isBlank() || !title.isBlank() || !aria.isBlank() || !candidate.isBlank())) {
                    items.put(new JSONObject()
                            .put("text", truncate(text, 200))
                            .put("title", title)
                            .put("ariaLabel", aria)
                            .put("href", href)
                            .put("timestampCandidate", candidate)
                            .put("parsedTimestamp", candidateParsed == null ? JSONObject.NULL : candidateParsed.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
                }
                if (parsed != null && items.length() >= 10) break;
            }
        } catch (Exception ignored) {}
        if (parsed == null) {
            List<String> lines = visibleLines(element);
            int commentSectionIndex = firstCommentSectionLineIndex(lines);
            if (commentSectionIndex >= 0) lines = new ArrayList<>(lines.subList(0, commentSectionIndex));
            selectedText = firstTimestampText(lines.toArray(new String[0]));
            parsed = parseFacebookTime(selectedText);
        }
        return new JSONObject()
                .put("hasTimestamp", items.length() > 0 || !selectedText.isBlank())
                .put("hasParsedTimestamp", parsed != null)
                .put("selectedText", selectedText)
                .put("selectedParsedTimestamp", parsed == null ? JSONObject.NULL : parsed.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .put("items", items);
    }

    private JSONObject actionData(WebElement element) throws JSONException {
        JSONArray likes = actionCandidates(element, "Like");
        JSONArray comments = actionCandidates(element, "Comment");
        JSONArray shares = actionCandidates(element, "Share");
        boolean hasCommentComposer = !element.findElements(By.xpath(".//div[@role='textbox' and @contenteditable='true']")).isEmpty();
        return new JSONObject()
                .put("hasLike", likes.length() > 0)
                .put("hasComment", comments.length() > 0)
                .put("hasCommentComposer", hasCommentComposer)
                .put("hasShare", shares.length() > 0)
                .put("likes", likes)
                .put("comments", comments)
                .put("shares", shares);
    }

    private JSONArray actionCandidates(WebElement root, String label) throws JSONException {
        JSONArray arr = new JSONArray();
        try {
            String lower = label.toLowerCase(Locale.ROOT);
            List<WebElement> els = root.findElements(By.xpath(".//*[(contains(@aria-label,'" + label + "') or contains(@aria-label,'" + lower + "') or normalize-space()='" + label + "')]"));
            for (int i = 0; i < Math.min(els.size(), 8); i++) {
                WebElement el = els.get(i);
                if (!safeDisplayed(el)) continue;
                arr.put(new JSONObject()
                        .put("tag", safeTag(el))
                        .put("text", truncate(safeElementText(el), 160))
                        .put("ariaLabel", safeAttr(el, "aria-label"))
                        .put("role", safeAttr(el, "role")));
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private JSONObject linkData(WebElement element) throws JSONException {
        JSONArray links = new JSONArray();
        boolean hasPostUrl = false;
        try {
            List<WebElement> els = element.findElements(By.xpath(".//a[@href]"));
            for (int i = 0; i < Math.min(els.size(), 25); i++) {
                WebElement el = els.get(i);
                String href = safeAttr(el, "href");
                if (href.contains("/posts/") || href.contains("/permalink/") || (href.contains("/groups/") && href.contains("/posts/"))) {
                    hasPostUrl = true;
                }
                links.put(new JSONObject()
                        .put("href", href)
                        .put("text", truncate(safeElementText(el), 160))
                        .put("ariaLabel", safeAttr(el, "aria-label")));
            }
        } catch (Exception ignored) {}
        return new JSONObject().put("hasPostUrl", hasPostUrl).put("items", links);
    }

    private JSONObject attributes(WebElement element) throws JSONException {
        return new JSONObject()
                .put("tag", safeTag(element))
                .put("id", safeAttr(element, "id"))
                .put("role", safeAttr(element, "role"))
                .put("ariaLabel", safeAttr(element, "aria-label"))
                .put("dataPagelet", safeAttr(element, "data-pagelet"))
                .put("dataTestId", safeAttr(element, "data-testid"))
                .put("class", truncate(safeAttr(element, "class"), 1000));
    }

    private JSONObject rect(WebElement element) {
        try {
            Object result = js.executeScript(
                    "var r=arguments[0].getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,top:r.top,left:r.left,bottom:r.bottom,right:r.right};",
                    element);
            return new JSONObject((Map<?, ?>) result);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject browserContext() throws JSONException {
        return new JSONObject()
                .put("userAgent", jsString("return navigator.userAgent"))
                .put("navigatorLanguage", jsString("return navigator.language"))
                .put("navigatorLanguages", jsValue("return navigator.languages"))
                .put("htmlLang", jsString("return document.documentElement.lang || ''"))
                .put("timezone", jsString("return Intl.DateTimeFormat().resolvedOptions().timeZone || ''"))
                .put("readyState", jsString("return document.readyState"))
                .put("viewport", jsValue("return {width: window.innerWidth, height: window.innerHeight, devicePixelRatio: window.devicePixelRatio}"))
                .put("cookies", cookies());
    }

    private JSONObject buildManifest() throws JSONException {
        return browserContext()
                .put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .put("runDir", runDir.toString())
                .put("workerId", workerId)
                .put("purpose", "Facebook post detection debug capture. No likes or comments are performed.");
    }

    private JSONArray matchedKeywords(String text) throws JSONException {
        String lower = text.toLowerCase(Locale.ROOT);
        String[] keywords = {"help", "advice", "?", "diet", "weight", "pain", "tips", "similar experience", "symptoms", "food", "aching", "success stories"};
        JSONArray matches = new JSONArray();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) matches.put(keyword);
        }
        return matches;
    }

    private String cleanPostText(WebElement post) {
        try {
            List<WebElement> els = post.findElements(By.xpath(
                    ".//div[@data-ad-preview='message'] | .//div[contains(@data-ad-preview,'message')]"));
            String text = els.stream().map(this::safeElementText).filter(t -> t.length() > 20)
                    .collect(Collectors.joining("\n"))
                    .replaceAll("\\s+", " ").trim();
            if (!text.isBlank()) return stripLeadingObfuscatedText(text);

            List<String> lines = Arrays.stream(safeElementText(post).split("\\R"))
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

    private List<String> visibleLines(WebElement element) {
        return Arrays.stream(safeElementText(element).split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(Collectors.toList());
    }

    private String firstTimestampText(String... values) {
        for (String value : values) {
            if (value == null) continue;
            for (String piece : value.split("[\\r\\n·•]")) {
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

    private JSONArray cookies() throws JSONException {
        JSONArray result = new JSONArray();
        try {
            for (Cookie cookie : driver.manage().getCookies()) {
                String name = cookie.getName();
                if ("locale".equalsIgnoreCase(name) || "wd".equalsIgnoreCase(name) || "datr".equalsIgnoreCase(name)) {
                    result.put(new JSONObject().put("name", name).put("value", cookie.getValue()));
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void scrollFeed() {
        for (int i = 0; i < 8; i++) {
            try {
                js.executeScript("window.scrollBy(0, Math.max(window.innerHeight * 0.85, 900));");
                sleep(1500);
            } catch (Exception ignored) {}
        }
        try { js.executeScript("window.scrollTo(0, 0);"); } catch (Exception ignored) {}
        sleep(1000);
    }

    private void waitForPageLoad() {
        for (int i = 0; i < 30; i++) {
            try {
                if ("complete".equals(js.executeScript("return document.readyState"))) return;
            } catch (Exception ignored) {}
            sleep(1000);
        }
    }

    private String screenshotPage(String fileName) {
        try {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Path target = screenshotsDir.resolve(fileName);
            Files.copy(src.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            return runDir.relativize(target).toString();
        } catch (Exception e) {
            writeError("screenshot_page", 0, fileName, e);
            return "";
        }
    }

    private String screenshotElement(WebElement element, String fileName) {
        try {
            File src = element.getScreenshotAs(OutputType.FILE);
            Path target = screenshotsDir.resolve(fileName);
            Files.copy(src.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            return runDir.relativize(target).toString();
        } catch (Exception e) {
            writeError("screenshot_element", 0, fileName, e);
            return "";
        }
    }

    private boolean isNestedArticle(WebElement element) {
        try {
            Object parentArticle = js.executeScript("return arguments[0].parentElement ? arguments[0].parentElement.closest('div[role=\"article\"]') : null;", element);
            return parentArticle != null;
        } catch (Exception e) { return false; }
    }

    private boolean isCommentSurface(WebElement element) {
        try {
            Object commentAncestor = js.executeScript(
                    "return arguments[0].parentElement ? arguments[0].parentElement.closest('[aria-label*=\"Comment\"], [aria-label*=\"comment\"], [data-testid*=\"comment\"], [class*=\"comment\"], [id*=\"comment\"]') : null;",
                    element);
            return commentAncestor != null;
        } catch (Exception e) { return false; }
    }

    private String outerHtml(WebElement element) {
        try { return String.valueOf(js.executeScript("return arguments[0].outerHTML || ''", element)); }
        catch (Exception e) { return ""; }
    }

    private Object jsValue(String script) {
        try { return JSONObject.wrap(js.executeScript(script)); }
        catch (Exception e) { return JSONObject.NULL; }
    }

    private String jsString(String script) {
        try { return String.valueOf(js.executeScript(script)); }
        catch (Exception e) { return ""; }
    }

    private String safeUrl() {
        try { return driver.getCurrentUrl(); } catch (Exception e) { return ""; }
    }

    private String safeTitle() {
        try { return driver.getTitle(); } catch (Exception e) { return ""; }
    }

    private String safeTag(WebElement element) {
        try { return element.getTagName(); } catch (Exception e) { return ""; }
    }

    private String safeAttr(WebElement element, String attr) {
        try {
            String value = element.getAttribute(attr);
            return value == null ? "" : value;
        } catch (Exception e) { return ""; }
    }

    private String safeElementText(WebElement element) {
        try {
            String text = element.getText();
            return text == null ? "" : text;
        } catch (Exception e) { return ""; }
    }

    private boolean safeDisplayed(WebElement element) {
        try { return element.isDisplayed(); } catch (Exception e) { return false; }
    }

    private int elementHeight(WebElement element) {
        try { return element.getRect().getHeight(); }
        catch (Exception e) { return 0; }
    }

    private boolean isLoadingSkeleton(WebElement element) {
        try {
            String html = String.valueOf(js.executeScript("return arguments[0].outerHTML.substring(0, 2500);", element))
                    .toLowerCase(Locale.ROOT);
            return html.contains("loading-state") || html.contains("aria-label=\"loading") || html.contains("suspended-feed");
        } catch (Exception e) { return false; }
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private void writeJson(Path path, JSONObject json) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json.toString(2), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log(workerId, warn("Failed to write debug JSON: " + e.getMessage()));
        }
    }

    private void appendJsonLine(Path path, JSONObject json) {
        try {
            Files.writeString(path, json.toString() + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log(workerId, warn("Failed to append debug JSON: " + e.getMessage()));
        }
    }

    private void writeError(String type, int index, String context, Exception e) {
        try {
            appendJsonLine(errorsFile, new JSONObject()
                    .put("type", type)
                    .put("index", index)
                    .put("context", context)
                    .put("message", e.getMessage())
                    .put("class", e.getClass().getName())
                    .put("at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        } catch (JSONException jsonException) {
            log(workerId, warn("Failed to record debug error: " + jsonException.getMessage()));
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
