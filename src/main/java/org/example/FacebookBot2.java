package org.example;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FacebookBot2 {
    private static final Path LOG_FILE = Paths.get("bot_interactions.log");
    private static final Path PROCESSED_POSTS_FILE = Paths.get("processed_posts.txt");
    private static final String CLI_DIR_NAME = ".facebookbot";
    private static final String JAR_NAME = "facebookbot.jar";

    // ANSI colors
    static final String RST = "\u001B[0m";
    static final String GRN = "\u001B[32m";
    static final String RED = "\u001B[31m";
    static final String YLW = "\u001B[33m";
    static final String CYN = "\u001B[36m";
    static final String BLU = "\u001B[34m";
    static final String BLD = "\u001B[1m";

    static String workerTag(int id) {
        return CYN + "[W" + id + "]" + RST;
    }

    static String ok(String msg) {
        return GRN + "\u2713 " + msg + RST;
    }

    static String fail(String msg) {
        return RED + "\u2717 " + msg + RST;
    }

    static String warn(String msg) {
        return YLW + "\u25cf " + msg + RST;
    }

    static String info(String msg) {
        return BLU + "\u25b6 " + msg + RST;
    }

    static void log(int workerId, String msg) {
        System.out.println("  " + workerTag(workerId) + " " + msg);
    }

    static void header(String title, String subtitle) {
        String line = "\u2550".repeat(58);
        System.out.println();
        System.out.println("  " + BLD + "\u2554" + line + "\u2557" + RST);
        System.out.println("  " + BLD + "\u2551" + RST + "  " + BLD + title + RST);
        if (subtitle != null) {
            System.out.println("  " + BLD + "\u2551" + RST + "  " + subtitle);
        }
        System.out.println("  " + BLD + "\u255A" + line + "\u255D" + RST);
        System.out.println();
    }

    public static class AccountInfo {
        final String email;
        final String password;

        public AccountInfo(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static class BotConfig {
        final List<AccountInfo> accounts;
        final String model;
        final int maxInteractions;
        final boolean headless;
        final boolean debugPostDetection;
        final int debugGroupLimit;

        public BotConfig(List<AccountInfo> accounts, String model, int maxInteractions, boolean headless,
                         boolean debugPostDetection, int debugGroupLimit) {
            this.accounts = accounts;
            this.model = model;
            this.maxInteractions = maxInteractions;
            this.headless = headless;
            this.debugPostDetection = debugPostDetection;
            this.debugGroupLimit = debugGroupLimit;
        }
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            switch (args[0]) {
                case "--install":
                case "-i":
                    handleInstall();
                    return;
                case "--uninstall":
                    handleUninstall();
                    return;
                case "--help":
                case "-h":
                    printHelp();
                    return;
            }
        }

        if (!isRunningInTerminal()) {
            if (launchInTerminal()) {
                return;
            }
            System.out.println("  " + warn("Could not open terminal. Running in console mode..."));
        }

        // We are the interactive run now: never let the window close silently on
        // bad input, a login failure, or a crash, so errors stay readable for debugging.
        try {
            runBot();
        } catch (Throwable t) {
            System.out.println("\n  " + fail("Unexpected error: " + t.getMessage()));
            t.printStackTrace(System.out);
        } finally {
            pauseBeforeExit();
        }
    }

    private static void runBot() {
        BotConfig config = interactiveSetup();
        if (config == null) {
            System.out.println("\n  " + fail("Setup cancelled or invalid input"));
            return;
        }

        Set<String> processedPosts = loadProcessedPosts();
        AtomicInteger totalInteractions = new AtomicInteger(0);
        OkHttpClient httpClient = new OkHttpClient();
        int workerCount = config.debugPostDetection ? 1 : config.accounts.size();
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);

        header("FacebookBot2 - Running",
                "Model: " + config.model + " (Ollama)  |  Interactions: 0/" + config.maxInteractions);

        for (int i = 0; i < workerCount; i++) {
            BotWorker worker = new BotWorker(
                    i + 1, config.accounts.get(i), config,
                    totalInteractions, processedPosts, httpClient);
            executor.submit(worker);
        }

        executor.shutdown();
        try {
            while (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                int count = totalInteractions.get();
                if (count >= config.maxInteractions) {
                    executor.shutdownNow();
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        saveProcessedPosts(processedPosts);

        System.out.println();
        String line = "\u2550".repeat(58);
        System.out.println("  " + BLD + "\u2554" + line + "\u2557" + RST);
        int finalCount = totalInteractions.get();
        System.out.println("  " + BLD + "\u2551" + RST + "  " + (finalCount >= config.maxInteractions ? GRN : YLW) +
                "Done. Total interactions: " + finalCount + "/" + config.maxInteractions + RST);
        System.out.println("  " + BLD + "\u255A" + line + "\u255D" + RST);
        System.out.println();
    }

    /**
     * Keeps the terminal window open until the user acknowledges, so error output
     * stays readable instead of the window vanishing on exit. Returns immediately
     * if stdin is not interactive (e.g. piped input) so automated runs don't hang.
     */
    private static void pauseBeforeExit() {
        // When relaunched through a wrapper script (a real terminal window), the
        // script itself reliably pauses on exit even when Java has no Console, so
        // skip here to avoid prompting the user twice.
        if ("true".equals(System.getenv("FACEBOOKBOT_IN_TERMINAL"))) {
            return;
        }
        System.out.println();
        System.out.print("  " + YLW + "Press Enter to close..." + RST + " ");
        System.out.flush();
        try {
            Console console = System.console();
            if (console != null) {
                console.readLine();
            } else {
                int c;
                while ((c = System.in.read()) != -1 && c != '\n') {
                    // drain the rest of the line
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isRunningInTerminal() {
        if ("true".equals(System.getenv("FACEBOOKBOT_IN_TERMINAL"))) return true;
        return System.console() != null;
    }

    private static boolean launchInTerminal() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        String mainClass = FacebookBot2.class.getName();

        try {
            if (os.contains("win")) {
                Path bat = Paths.get(System.getProperty("user.dir"), "facebookbot_run.bat");
                Files.write(bat, List.of(
                        "@echo off",
                        "title FacebookBot2",
                        "set FACEBOOKBOT_IN_TERMINAL=true",
                        "\"" + javaBin + "\" -cp \"" + classpath + "\" " + mainClass,
                        "echo.",
                        "pause"
                ));
                Runtime.getRuntime().exec("cmd.exe /c start \"\" \"" + bat.toAbsolutePath() + "\"");
                return true;
            } else if (os.contains("mac")) {
                Path script = Files.createTempFile("facebookbot", ".sh");
                String escapedDir = System.getProperty("user.dir").replace("'", "'\\''");
                String escapedCp = classpath.replace("'", "'\\''");
                Files.write(script, List.of(
                        "#!/bin/bash",
                        "export FACEBOOKBOT_IN_TERMINAL=true",
                        "cd '" + escapedDir + "'",
                        "\"" + javaBin + "\" -cp '" + escapedCp + "' " + mainClass,
                        "code=$?",
                        "echo",
                        "read -p \"Press Enter to close...\" _",
                        "exit $code"
                ));
                script.toFile().setExecutable(true);
                Runtime.getRuntime().exec(new String[]{
                        "osascript", "-e",
                        "tell app \"Terminal\" to do script \"" + script.toAbsolutePath() + "\""
                });
                return true;
            } else if (os.contains("nix") || os.contains("nux") || os.contains("linux")) {
                Path script = Files.createTempFile("facebookbot", ".sh");
                String escapedDir = System.getProperty("user.dir").replace("'", "'\\''");
                String escapedCp = classpath.replace("'", "'\\''");
                Files.write(script, List.of(
                        "#!/bin/bash",
                        "export FACEBOOKBOT_IN_TERMINAL=true",
                        "cd '" + escapedDir + "'",
                        "\"" + javaBin + "\" -cp '" + escapedCp + "' " + mainClass,
                        "code=$?",
                        "echo",
                        "read -p \"Press Enter to close...\" _",
                        "exit $code"
                ));
                script.toFile().setExecutable(true);
                String[] terminals = {"x-terminal-emulator", "gnome-terminal", "xfce4-terminal", "konsole", "lxterminal", "xterm"};
                for (String term : terminals) {
                    try {
                        Runtime.getRuntime().exec(new String[]{term, "-e", script.toAbsolutePath().toString()});
                        return true;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("  " + fail("Could not open terminal: " + e.getMessage()));
        }
        return false;
    }

    private static void handleInstall() {
        try {
            Path jarPath = findJarPath();
            if (jarPath == null) {
                System.out.println("  " + fail("Could not find FacebookBot2.jar."));
                System.out.println("  " + info("Build the project first: mvn package"));
                return;
            }

            Path installDir = Paths.get(System.getProperty("user.home"), CLI_DIR_NAME);
            Files.createDirectories(installDir);

            Path targetJar = installDir.resolve(JAR_NAME);
            Files.copy(jarPath, targetJar, StandardCopyOption.REPLACE_EXISTING);

            createLauncherScript(installDir);
            addInstallDirToPath(installDir);

            System.out.println("  " + ok("FacebookBot2 CLI installed!"));
            System.out.println("  " + info("Install location: " + installDir));
            System.out.println("  " + info("Run 'facebookbot' from anywhere to start the bot."));
            System.out.println("  " + warn("You may need to restart your terminal or run 'source ~/.bashrc' (or equivalent)."));
        } catch (IOException e) {
            System.out.println("  " + fail("Installation failed: " + e.getMessage()));
        }
    }

    private static void handleUninstall() {
        try {
            Path installDir = Paths.get(System.getProperty("user.home"), CLI_DIR_NAME);
            if (Files.exists(installDir)) {
                try (var files = Files.walk(installDir)) {
                    files.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                }
                removeInstallDirFromPath(installDir);
                System.out.println("  " + ok("FacebookBot2 CLI uninstalled."));
            } else {
                System.out.println("  " + warn("FacebookBot2 CLI is not installed."));
            }
        } catch (IOException e) {
            System.out.println("  " + fail("Uninstall failed: " + e.getMessage()));
        }
    }

    private static void printHelp() {
        header("FacebookBot2 Help", "Usage and commands");
        System.out.println("  " + BLD + "Commands:" + RST);
        System.out.println("    facebookbot              Start the bot interactively");
        System.out.println("    facebookbot --install     Install CLI command");
        System.out.println("    facebookbot --uninstall   Remove CLI command");
        System.out.println("    facebookbot --help        Show this help");
        System.out.println();
        System.out.println("  " + BLD + "IntelliJ users:" + RST);
        System.out.println("    Running from IntelliJ automatically opens an OS terminal window.");
        System.out.println("    For persistent CLI use, run --install after building.");
        System.out.println();
    }

    private static Path findJarPath() {
        String classpath = System.getProperty("java.class.path");
        String sep = File.pathSeparator;
        for (String entry : classpath.split(sep)) {
            if (entry.endsWith(".jar") && entry.contains("FacebookBot")) {
                return Paths.get(entry);
            }
        }
        Path target = Paths.get("target");
        if (Files.exists(target)) {
            try (var files = Files.list(target)) {
                return files.filter(p -> p.toString().endsWith(".jar") && !p.toString().contains("original"))
                        .findFirst().orElse(null);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static void createLauncherScript(Path installDir) throws IOException {
        Path jarPath = installDir.resolve(JAR_NAME);
        if (isWindows()) {
            Files.write(installDir.resolve("facebookbot.bat"), List.of(
                    "@echo off",
                    "set FACEBOOKBOT_IN_TERMINAL=true",
                    "\"" + System.getProperty("java.home") + "\\bin\\java\" -jar \"" + jarPath.toAbsolutePath() + "\" %*",
                    "echo.",
                    "pause"
            ));
        } else {
            Path script = installDir.resolve("facebookbot");
            Files.write(script, List.of(
                    "#!/bin/bash",
                    "export FACEBOOKBOT_IN_TERMINAL=true",
                    "\"" + System.getProperty("java.home") + "/bin/java\" -jar \"" + jarPath.toAbsolutePath() + "\" \"$@\"",
                    "code=$?",
                    "echo",
                    "read -p \"Press Enter to close...\" _",
                    "exit $code"
            ));
            script.toFile().setExecutable(true);
        }
    }

    private static void addInstallDirToPath(Path installDir) {
        String path = installDir.toAbsolutePath().toString();
        if (isWindows()) {
            try {
                String currentPath = System.getenv("Path");
                if (currentPath != null && !currentPath.contains(path)) {
                    Runtime.getRuntime().exec(new String[]{"setx", "Path", currentPath + ";" + path});
                }
            } catch (IOException e) {
                System.out.println("  " + warn("Could not update PATH automatically."));
                System.out.println("  " + info("Manually add this to your PATH: " + path));
            }
        } else {
            String rcFile = ".bashrc";
            String shell = System.getenv("SHELL");
            if (shell != null && shell.contains("zsh")) rcFile = ".zshrc";

            try {
                Path home = Paths.get(System.getProperty("user.home"));
                Path rc = home.resolve(rcFile);
                String exportLine = "export PATH=\"" + path + ":$PATH\"";
                if (Files.exists(rc)) {
                    String content = Files.readString(rc);
                    if (!content.contains(path)) {
                        Files.writeString(rc, content + "\n" + exportLine + "\n");
                        System.out.println("  " + ok("Added to " + rcFile));
                    } else {
                        System.out.println("  " + info("Already in " + rcFile));
                    }
                } else {
                    Files.writeString(rc, exportLine + "\n");
                    System.out.println("  " + ok("Created " + rcFile + " with PATH entry"));
                }
            } catch (IOException e) {
                System.out.println("  " + warn("Could not update shell config automatically."));
                System.out.println("  " + info("Add this to your shell config: export PATH=\"" + path + ":$PATH\""));
            }
        }
    }

    private static void removeInstallDirFromPath(Path installDir) {
        String path = installDir.toAbsolutePath().toString();
        if (isWindows()) {
            try {
                String currentPath = System.getenv("Path");
                if (currentPath != null && currentPath.contains(path)) {
                    String newPath = currentPath
                            .replace(";" + path, "")
                            .replace(path + ";", "")
                            .replace(path, "");
                    Runtime.getRuntime().exec(new String[]{"setx", "Path", newPath});
                }
            } catch (IOException e) {
                System.out.println("  " + warn("Could not update PATH automatically."));
            }
        } else {
            String[] rcs = {".bashrc", ".zshrc", ".profile"};
            Path home = Paths.get(System.getProperty("user.home"));
            for (String rcName : rcs) {
                try {
                    Path rc = home.resolve(rcName);
                    if (Files.exists(rc)) {
                        String content = Files.readString(rc);
                        String exportLine = "export PATH=\"" + path + ":$PATH\"";
                        if (content.contains(exportLine)) {
                            content = content.replace(exportLine + "\n", "").replace(exportLine, "");
                            Files.writeString(rc, content);
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static BotConfig interactiveSetup() {
        Console console = System.console();
        Scanner scanner = console == null ? new Scanner(System.in) : null;

        header("SpaveCraft Bot", "Facebook Marketing Agent");

        // Number of accounts (re-prompts until a valid positive number is given)
        int numAccounts = promptPositiveInt("How many Facebook accounts", null, console, scanner);

        List<AccountInfo> accounts = new ArrayList<>();
        for (int i = 0; i < numAccounts; i++) {
            System.out.println("\n  " + CYN + "\u2500".repeat(30) + " Account #" + (i + 1) + " " + "\u2500".repeat(30) + RST);
            String email = promptRequired("  Email", false, console, scanner);
            String password = promptRequired("  Password", true, console, scanner);
            accounts.add(new AccountInfo(email.trim(), password));
        }

        // Options
        System.out.println("\n  " + CYN + "\u2500".repeat(30) + " Options " + "\u2500".repeat(31) + RST);
        String model = promptInput("  Ollama model", "gemma4:2b", false, console, scanner);
        if (model == null || model.isBlank()) model = "gemma4:2b";

        int maxInteractions = promptPositiveInt("  Max total interactions", 70, console, scanner);

        String headlessStr = promptInput("  Headless mode? (y/N)", "n", false, console, scanner);
        boolean headless = headlessStr.equalsIgnoreCase("y") || headlessStr.equalsIgnoreCase("yes");

        String debugStr = promptInput("  Debug post detection only? (y/N)", "n", false, console, scanner);
        boolean debugPostDetection = debugStr.equalsIgnoreCase("y") || debugStr.equalsIgnoreCase("yes");
        int debugGroupLimit = debugPostDetection
                ? promptPositiveInt("  Debug group capture limit", 5, console, scanner)
                : 0;

        System.out.println();
        return new BotConfig(accounts, model, maxInteractions, headless, debugPostDetection, debugGroupLimit);
    }

    /**
     * Repeatedly prompts until the user enters a positive whole number. Pressing Enter
     * with no value uses {@code defaultValue} when one is provided. Returns the default
     * (or aborts via exception when there is none) only if the input stream reaches EOF.
     */
    private static int promptPositiveInt(String label, Integer defaultValue, Console console, Scanner scanner) {
        String def = defaultValue == null ? null : String.valueOf(defaultValue);
        while (true) {
            String input = promptInput(label, def, false, console, scanner);
            if (input == null) { // EOF (e.g. Ctrl-D) — nothing more to read
                if (defaultValue != null) return defaultValue;
                throw new IllegalStateException("No input available for: " + label.trim());
            }
            try {
                int value = Integer.parseInt(input.trim());
                if (value >= 1) return value;
            } catch (NumberFormatException ignored) {
            }
            System.out.println("  " + fail("Please enter a whole number greater than 0, then press Enter."));
        }
    }

    /**
     * Repeatedly prompts until the user enters a non-blank value. Aborts via exception
     * only if the input stream reaches EOF with nothing entered.
     */
    private static String promptRequired(String label, boolean secret, Console console, Scanner scanner) {
        while (true) {
            String input = promptInput(label, null, secret, console, scanner);
            if (input == null) { // EOF
                throw new IllegalStateException("No input available for: " + label.trim());
            }
            if (!input.isBlank()) return input;
            System.out.println("  " + fail("This field can't be empty, please try again."));
        }
    }

    /**
     * Prints the prompt and reads one line. Returns {@code null} only on EOF so callers
     * can tell "user pressed Enter" (empty string) apart from "no more input". A blank
     * line falls back to {@code defaultValue} when one is supplied.
     */
    private static String promptInput(String label, String defaultValue, boolean secret, Console console, Scanner scanner) {
        String prompt = "  " + BLD + label + RST;
        if (defaultValue != null) {
            prompt += " [" + defaultValue + "]";
        }
        prompt += " > ";

        String input;
        if (secret && console != null) {
            char[] chars = console.readPassword(prompt);
            input = chars == null ? null : new String(chars);
        } else if (console != null) {
            input = console.readLine(prompt);
        } else {
            System.out.print(prompt);
            try {
                input = scanner.nextLine();
            } catch (NoSuchElementException e) {
                input = null; // EOF
            }
        }

        if (input == null) return defaultValue;                  // EOF -> default (may be null)
        if (input.isBlank() && defaultValue != null) return defaultValue;
        return input;
    }

    // --- Shared helpers ---

    static String askAI(OkHttpClient client, String model, String prompt) throws IOException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                JSONObject body = new JSONObject()
                        .put("model", model)
                        .put("messages", new JSONArray()
                                .put(new JSONObject().put("role", "user").put("content", prompt)))
                        .put("temperature", 0.7)
                        .put("max_tokens", 500)
                        .put("stream", false);

                Request request = new Request.Builder()
                        .url("http://localhost:11434/v1/chat/completions")
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body().string();
                        throw new IOException("Ollama error (" + response.code() + "): " + errorBody);
                    }

                    JSONObject json = new JSONObject(response.body().string());
                    if (json.has("choices") && json.getJSONArray("choices").length() > 0) {
                        return json.getJSONArray("choices")
                                .getJSONObject(0).getJSONObject("message").getString("content");
                    }
                }
            } catch (Exception e) {
                if (attempt < 3) {
                    System.err.println("  " + warn("Ollama call failed (attempt " + attempt + "): " + e.getMessage()));
                    try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                } else {
                    throw new IOException("Ollama failed after 3 attempts: " + e.getMessage());
                }
            }
        }
        throw new IOException("Ollama call failed");
    }

    private static Set<String> loadProcessedPosts() {
        Set<String> posts = ConcurrentHashMap.newKeySet();
        try {
            if (Files.exists(PROCESSED_POSTS_FILE)) {
                posts.addAll(Files.readAllLines(PROCESSED_POSTS_FILE));
            }
        } catch (Exception e) {
            System.err.println("  " + warn("Could not load processed posts: " + e.getMessage()));
        }
        return posts;
    }

    private static void saveProcessedPosts(Set<String> processedPosts) {
        try {
            Files.write(PROCESSED_POSTS_FILE, processedPosts, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("  " + warn("Failed to save processed posts: " + e.getMessage()));
        }
    }

    static void logInteraction(String postText, String reply) {
        String logEntry = String.format("[%s] %s -> %s\n",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                postText.substring(0, Math.min(50, postText.length())),
                reply.substring(0, Math.min(50, reply.length())));
        try {
            Files.write(LOG_FILE, logEntry.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("  " + warn("Failed to log interaction: " + e.getMessage()));
        }
    }
}
