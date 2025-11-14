import bs.BlackScholesEngine;
import bs.OptionType;
import java.io.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/*
 MainSimulation.java
 - large menu-driven program (default package)
 - works with bs.BlackScholesEngine and bs.OptionType
 - includes error handling, file handling, OOP helpers, validation, logging, session history
 - comments intentionally short
*/

public class MainSimulation {

    // formatting
    private static final DecimalFormat DF6 = new DecimalFormat("0.000000");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // single shared engine instance
    private static final BlackScholesEngine engine = new BlackScholesEngine();

    // scanner for input
    private static final Scanner SC = new Scanner(System.in);

    // session state
    private static final List<String> sessionLog = new ArrayList<>();
    private static final String MAIN_LOG_FILE = "bs_sim_log.txt";
    private static final String HISTORY_FILE = "bs_session_history.txt";

    // ANSI colors (may not work on Windows cmd without enabling)
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN = "\u001B[36m";

    // entry point
    public static void main(String[] args) {
        printWelcome();
        boolean running = true;
        // attempt to load session history at start
        loadHistory();
        while (running) {
            printMenu();
            int choice = InputUtil.readInt("Choice: ", 1, 14);
            try {
                switch (choice) {
                    case 1 -> analyticFlow();
                    case 2 -> monteCarloFlow();
                    case 3 -> impliedVolFlow();
                    case 4 -> batchCsvFlow();
                    case 5 -> saveExampleCsv();
                    case 6 -> asciiHistogramDemo();
                    case 7 -> greeksFlow();
                    case 8 -> selfTestFlow();
                    case 9 -> showLog();
                    case 10 -> exportSessionHistory();
                    case 11 -> clearLogFlow();
                    case 12 -> validateAndRepairCsvFlow();
                    case 13 -> advancedOptionsMenu();
                    case 14 -> { running = false; trace("Exit"); }
                    default -> printlnWarn("Unknown choice");
                }
            } catch (Exception ex) {
                String msg = "Unhandled error: " + ex.getClass().getSimpleName() + " - " + ex.getMessage();
                printlnError(msg);
                trace(msg);
            }
        }
        saveHistory();
        System.out.println("Goodbye!");
        SC.close();
    }

    // small welcome
    private static void printWelcome() {
        System.out.println(ANSI_CYAN + "Black-Scholes Simulation Suite (Simple Edition)" + ANSI_RESET);
        System.out.println("Using package bs.BlackScholesEngine and bs.OptionType");
        System.out.println("Log file: " + MAIN_LOG_FILE + "   History: " + HISTORY_FILE);
    }

    // menu
    private static void printMenu() {
        System.out.println("\n--- MAIN MENU ---");
        System.out.println("1) Analytic Black-Scholes price");
        System.out.println("2) Monte Carlo pricing (standard/antithetic)");
        System.out.println("3) Implied volatility (Black-Scholes)");
        System.out.println("4) Batch CSV: read options.csv -> results.csv");
        System.out.println("5) Save example CSV template");
        System.out.println("6) ASCII histogram (MC terminal prices)");
        System.out.println("7) Show Greeks for an option");
        System.out.println("8) Self-test demo");
        System.out.println("9) Show simulation log (last lines)");
        System.out.println("10) Export session history to CSV");
        System.out.println("11) Clear main log file");
        System.out.println("12) Validate & attempt repair of CSV file");
        System.out.println("13) Advanced options");
        System.out.println("14) Exit");
    }

    // ---------- primary flows ----------

    // analytic pricing
    private static void analyticFlow() {
        printHeader("Analytic Black-Scholes");
        BlackScholesEngine.OptionContract oc = promptOptionContract();
        double price = safeAnalyticPrice(oc);
        System.out.println("Analytic price = " + DF6.format(price));
        printlnInfo(engine.analyticGreeks(oc));
        String entry = logEntry("ANALYTIC", oc, price);
        persistLog(entry);
    }

    // Monte Carlo
    private static void monteCarloFlow() {
        printHeader("Monte Carlo Pricing");
        BlackScholesEngine.OptionContract oc = promptOptionContract();
        int nsim = (int) InputUtil.readDouble("Simulations (100 - 5,000,000): ", 100, 5_000_000);
        long seed = (long) InputUtil.readDouble("Seed (0 for random): ", 0, Long.MAX_VALUE);
        int method = InputUtil.readInt("Method: 1=Standard  2=Antithetic: ", 1, 2);
        boolean antithetic = (method == 2);
        printlnInfo("Running MC... This may take time for large nsim.");
        long t0 = System.currentTimeMillis();
        double price = safeMonteCarloPrice(oc, nsim, seed, antithetic);
        long t1 = System.currentTimeMillis();
        System.out.println("MC price = " + DF6.format(price) + "   Time(ms): " + (t1 - t0));
        String entry = logEntry("MC", oc, price, "nsim=" + nsim + ",antithetic=" + antithetic + ",seed=" + seed);
        persistLog(entry);
    }

    // implied volatility
    private static void impliedVolFlow() {
        printHeader("Implied Volatility (BS)");
        BlackScholesEngine.OptionContract oc = promptOptionContract();
        double market = InputUtil.readDouble("Market price: ", 0.0, Double.MAX_VALUE);
        printlnInfo("Solving... may return -1 if not found");
        double iv = safeImpliedVol(oc, market, 1e-6, 300);
        if (iv < 0) {
            printlnWarn("Implied vol not found (try different initial sigma or bounds)");
        } else {
            System.out.println("Implied volatility ≈ " + DF6.format(iv));
        }
        String entry = logEntry("IMPLIED_VOL", oc, iv, "market=" + market);
        persistLog(entry);
    }

    // batch CSV
    private static void batchCsvFlow() {
        printHeader("Batch CSV Processing");
        String inputFile = InputUtil.readString("Input CSV filename (default options.csv): ").trim();
        if (inputFile.isEmpty()) inputFile = "options.csv";
        List<BlackScholesEngine.OptionContract> list = engine.readOptionsCsv(inputFile);
        if (list.isEmpty()) {
            printlnWarn("No options read from " + inputFile);
            return;
        }
        String outFile = InputUtil.readString("Output CSV filename (default results.csv): ").trim();
        if (outFile.isEmpty()) outFile = "results.csv";
        List<String> rows = new ArrayList<>();
        rows.add("type,S,K,r,sigma,T,q,price");
        BlackScholesEngine pr = engine;
        for (BlackScholesEngine.OptionContract oc : list) {
            try {
                double p = pr.analyticPrice(oc);
                rows.add(String.format(Locale.US, "%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                        oc.type, oc.S, oc.K, oc.r, oc.sigma, oc.T, oc.q, p));
                System.out.println(oc + " -> " + DF6.format(p));
            } catch (Exception e) {
                System.out.println("Skipping option due to error: " + oc + " (" + e.getMessage() + ")");
            }
        }
        engine.writeResultsCsv(outFile, rows);
        engine.appendLog(MAIN_LOG_FILE, "Batch processed " + list.size() + " options -> " + outFile);
    }

    // save example csv
    private static void saveExampleCsv() {
        printHeader("Save Example CSV");
        List<String> rows = Arrays.asList(
                "# type,S,K,r,sigma,T,q",
                "EUROPEAN_CALL,100,100,0.05,0.2,1.0,0.0",
                "EUROPEAN_PUT,100,95,0.05,0.25,0.5,0.0",
                "BINARY_CALL,100,110,0.03,0.3,0.5,0.0",
                "DIGITAL_PUT,80,85,0.04,0.25,0.75,0.0"
        );
        engine.writeResultsCsv("options_example.csv", rows);
    }

    // ascii histogram demo
    private static void asciiHistogramDemo() {
        printHeader("ASCII Histogram Demo");
        BlackScholesEngine.OptionContract oc = promptOptionContract();
        int nsim = (int) InputUtil.readDouble("Simulations (e.g. 20000): ", 100, 1_000_000);
        long seed = (long) InputUtil.readDouble("Seed (0 random): ", 0, Long.MAX_VALUE);
        Random rng = (seed == 0) ? new Random() : new Random(seed);
        double[] samples = new double[nsim];
        for (int i = 0; i < nsim; i++) {
            double z = Math.sqrt(-2.0 * Math.log(rng.nextDouble())) * Math.cos(2.0 * Math.PI * rng.nextDouble());
            double ST = oc.S * Math.exp((oc.r - oc.q - 0.5 * oc.sigma * oc.sigma) * oc.T + oc.sigma * Math.sqrt(oc.T) * z);
            samples[i] = ST;
        }
        engine.asciiHistogram(samples, 30);
        engine.appendLog(MAIN_LOG_FILE, "Ascii histogram for " + oc + " nsim=" + nsim);
    }

    // greeks
    private static void greeksFlow() {
        printHeader("Greeks");
        BlackScholesEngine.OptionContract oc = promptOptionContract();
        double price = safeAnalyticPrice(oc);
        System.out.println("Price: " + DF6.format(price));
        System.out.println("Greeks: " + engine.analyticGreeks(oc));
        engine.appendLog(MAIN_LOG_FILE, "Greeks computed for " + oc);
    }

    // self-test
    private static void selfTestFlow() {
        printHeader("Self-Test Demo");
        engine.selfTest();
        engine.appendLog(MAIN_LOG_FILE, "Self-test executed");
    }

    // show log tail
    private static void showLog() {
        printHeader("Log (last lines)");
        File f = new File(MAIN_LOG_FILE);
        if (!f.exists()) {
            printlnWarn("No log file yet.");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            int start = Math.max(0, lines.size() - 50);
            for (int i = start; i < lines.size(); i++) System.out.println(lines.get(i));
        } catch (IOException e) {
            printlnError("Error reading log: " + e.getMessage());
        }
    }

    // export session history to CSV
    private static void exportSessionHistory() {
        printHeader("Export Session History");
        if (sessionLog.isEmpty()) {
            printlnWarn("Session history empty");
            return;
        }
        String out = InputUtil.readString("Output CSV filename (default session_history.csv): ").trim();
        if (out.isEmpty()) out = "session_history.csv";
        List<String> rows = new ArrayList<>();
        rows.add("timestamp,entry");
        for (String s : sessionLog) {
            rows.add(s.replace(",", ";")); // naive escape
        }
        engine.writeResultsCsv(out, rows);
        engine.appendLog(MAIN_LOG_FILE, "Session history exported -> " + out);
    }

    // clear log file
    private static void clearLogFlow() {
        printHeader("Clear Main Log");
        System.out.print("Are you sure? (yes to confirm): ");
        String ans = SC.next().trim();
        if ("yes".equalsIgnoreCase(ans)) {
            try (FileWriter fw = new FileWriter(MAIN_LOG_FILE, false)) {
                // truncate
            } catch (IOException e) {
                printlnError("Could not clear log: " + e.getMessage());
            }
            printlnInfo("Log cleared");
        } else {
            printlnInfo("Abort clear");
        }
    }

    // validate & attempt repair of CSV (simple)
    private static void validateAndRepairCsvFlow() {
        printHeader("Validate & Attempt Repair CSV");
        String in = InputUtil.readString("CSV filename to validate (default options.csv): ").trim();
        if (in.isEmpty()) in = "options.csv";
        List<String> badLines = new ArrayList<>();
        List<String> goodLines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(in))) {
            String line; int ln = 0;
            while ((line = br.readLine()) != null) {
                ln++;
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split(",");
                if (parts.length < 7) {
                    badLines.add(ln + ":" + t);
                    continue;
                }
                try {
                    OptionType.valueOf(parts[0].trim());
                    Double.parseDouble(parts[1].trim());
                    Double.parseDouble(parts[2].trim());
                    Double.parseDouble(parts[3].trim());
                    Double.parseDouble(parts[4].trim());
                    Double.parseDouble(parts[5].trim());
                    Double.parseDouble(parts[6].trim());
                    goodLines.add(t);
                } catch (Exception ex) {
                    badLines.add(ln + ":" + t);
                }
            }
        } catch (IOException e) {
            printlnError("Cannot read file: " + e.getMessage());
            return;
        }
        System.out.println("Good lines: " + goodLines.size() + "   Bad lines: " + badLines.size());
        if (!badLines.isEmpty()) {
            System.out.println("First bad lines:");
            for (int i = 0; i < Math.min(10, badLines.size()); i++) System.out.println(badLines.get(i));
            String out = InputUtil.readString("Save repaired file as (default options_repaired.csv): ").trim();
            if (out.isEmpty()) out = "options_repaired.csv";
            engine.writeResultsCsv(out, goodLines);
            engine.appendLog(MAIN_LOG_FILE, "CSV validated: " + in + " -> repaired: " + out);
        } else {
            printlnInfo("File looks OK");
        }
    }

    // advanced submenu
    private static void advancedOptionsMenu() {
        printHeader("Advanced Options");
        while (true) {
            System.out.println("\n--- Advanced ---");
            System.out.println("1) Run batch MC on CSV (per-row)");
            System.out.println("2) Multithreaded MC demo (simple)");
            System.out.println("3) Export log summary");
            System.out.println("4) Back");
            int c = InputUtil.readInt("Choice: ", 1, 4);
            if (c == 1) batchMonteCarloOnCsv();
            else if (c == 2) multithreadedMcDemo();
            else if (c == 3) exportLogSummary();
            else break;
        }
    }

    // batch Monte Carlo per CSV row
    private static void batchMonteCarloOnCsv() {
        printHeader("Batch MC per CSV");
        String in = InputUtil.readString("Input CSV (default options.csv): ").trim();
        if (in.isEmpty()) in = "options.csv";
        List<BlackScholesEngine.OptionContract> list = engine.readOptionsCsv(in);
        if (list.isEmpty()) {
            printlnWarn("No options read");
            return;
        }
        int nsim = (int) InputUtil.readDouble("Simulations per option (e.g. 20000): ", 100, 500_000);
        long seed = (long) InputUtil.readDouble("Base seed (0 random): ", 0, Long.MAX_VALUE);
        List<String> rows = new ArrayList<>();
        rows.add("type,S,K,r,sigma,T,q,mc_price");
        int idx = 0;
        for (var oc : list) {
            idx++;
            long s = (seed == 0) ? new Random().nextLong() : seed + idx;
            double price = engine.monteCarloPrice(oc, nsim, s, false);
            rows.add(String.format(Locale.US, "%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                    oc.type, oc.S, oc.K, oc.r, oc.sigma, oc.T, oc.q, price));
            System.out.println("Row " + idx + " -> " + DF6.format(price));
        }
        String out = InputUtil.readString("Output CSV (default mc_results.csv): ").trim();
        if (out.isEmpty()) out = "mc_results.csv";
        engine.writeResultsCsv(out, rows);
        engine.appendLog(MAIN_LOG_FILE, "Batch MC on " + in + " -> " + out);
    }

    // simple multithreaded MC demo (not optimized)
    private static void multithreadedMcDemo() {
        printHeader("Multithreaded MC Demo");
        BlackScholesEngine.OptionContract oc = promptOptionContract();
        int nsim = (int) InputUtil.readDouble("Total simulations (e.g. 200000): ", 1000, 5_000_000);
        int threads = (int) InputUtil.readDouble("Threads (1-16): ", 1, 16);
        long seed = (long) InputUtil.readDouble("Seed (0 random): ", 0, Long.MAX_VALUE);
        printlnInfo("Spawning " + threads + " worker(s)");
        long start = System.currentTimeMillis();
        double result = multithreadedMonteCarlo(oc, nsim, threads, seed);
        long end = System.currentTimeMillis();
        System.out.println("Multithreaded MC price = " + DF6.format(result) + " Time(ms): " + (end - start));
        engine.appendLog(MAIN_LOG_FILE, "Multithread MC nsim=" + nsim + " threads=" + threads + " -> " + result);
    }

    // export log summary (basic)
    private static void exportLogSummary() {
        printHeader("Export Log Summary");
        String out = InputUtil.readString("Output filename summary.csv (default log_summary.csv): ").trim();
        if (out.isEmpty()) out = "log_summary.csv";
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,level,message");
        try (BufferedReader br = new BufferedReader(new FileReader(MAIN_LOG_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(LocalDateTime.now().toString() + "," + line.replace(",", ";"));
            }
            engine.writeResultsCsv(out, lines);
            printlnInfo("Exported summary -> " + out);
        } catch (IOException e) {
            printlnError("Error exporting summary: " + e.getMessage());
        }
    }

    // ---------- utilities & helpers ----------

    // safe wrappers that catch exceptions and return NaN or -1
    private static double safeAnalyticPrice(BlackScholesEngine.OptionContract oc) {
        try {
            return engine.analyticPrice(oc);
        } catch (Exception e) {
            printlnError("Analytic price error: " + e.getMessage());
            return Double.NaN;
        }
    }

    private static double safeMonteCarloPrice(BlackScholesEngine.OptionContract oc, int nsim, long seed, boolean antithetic) {
        try {
            return engine.monteCarloPrice(oc, nsim, seed, antithetic);
        } catch (Exception e) {
            printlnError("Monte Carlo error: " + e.getMessage());
            return Double.NaN;
        }
    }

    private static double safeImpliedVol(BlackScholesEngine.OptionContract oc, double market, double tol, int maxIter) {
        try {
            return engine.impliedVol(oc, market, tol, maxIter);
        } catch (Exception e) {
            printlnError("Implied vol error: " + e.getMessage());
            return -1.0;
        }
    }

    // build log entry
    private static String logEntry(String tag, BlackScholesEngine.OptionContract oc, double value) {
        return logEntry(tag, oc, value, "");
    }

    private static String logEntry(String tag, BlackScholesEngine.OptionContract oc, double value, String meta) {
        String ts = LocalDateTime.now().format(TS_FMT);
        String entry = String.format("%s,%s,%s,%.6f,%s", ts, tag, oc.toString(), value, meta);
        sessionLog.add(entry);
        return entry;
    }

    // persist log to both main log file and internal session list
    private static void persistLog(String entry) {
        try {
            engine.appendLog(MAIN_LOG_FILE, entry);
        } catch (Exception e) {
            printlnError("Could not write to main log: " + e.getMessage());
        }
    }

    // prompt option contract interactively
    private static BlackScholesEngine.OptionContract promptOptionContract() {
        System.out.println("Choose option type:");
        System.out.println("1) EUROPEAN_CALL    2) EUROPEAN_PUT");
        System.out.println("3) BINARY_CALL      4) DIGITAL_PUT");
        int t = InputUtil.readInt("Type (1-4): ", 1, 4);
        OptionType type = switch (t) {
            case 1 -> OptionType.EUROPEAN_CALL;
            case 2 -> OptionType.EUROPEAN_PUT;
            case 3 -> OptionType.BINARY_CALL;
            default -> OptionType.DIGITAL_PUT;
        };
        double S = InputUtil.readDouble("Spot S: ", 1e-6, 1e12);
        double K = InputUtil.readDouble("Strike K: ", 1e-6, 1e12);
        double r = InputUtil.readDouble("Risk-free rate r (e.g. 0.05): ", -1.0, 5.0);
        double sigma = InputUtil.readDouble("Volatility sigma (e.g. 0.2): ", 1e-6, 10.0);
        double T = InputUtil.readDouble("Time to maturity T (years): ", 0.0, 100.0);
        double q = InputUtil.readDouble("Dividend yield q (e.g. 0): ", -1.0, 5.0);
        return new BlackScholesEngine.OptionContract(type, S, K, r, sigma, T, q);
    }

    // multithreaded monte carlo simple implementation
    private static double multithreadedMonteCarlo(BlackScholesEngine.OptionContract oc, int totalSim, int threads, long seed) {
        if (threads <= 1) return engine.monteCarloPrice(oc, totalSim, seed, false);
        final int perThread = Math.max(1, totalSim / threads);
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long s = (seed == 0) ? new Random().nextLong() : seed + i;
            int sims = (i == threads - 1) ? (totalSim - perThread * i) : perThread;
            workers.add(new Worker(oc, sims, s));
        }
        List<Thread> ts = new ArrayList<>();
        for (Worker w : workers) {
            Thread th = new Thread(w);
            ts.add(th);
            th.start();
        }
        double sum = 0.0;
        for (Thread th : ts) {
            try { th.join(); } catch (InterruptedException e) { printlnError("Thread join interrupted"); }
        }
        int totalDone = 0;
        for (Worker w : workers) {
            sum += w.getSum();
            totalDone += w.getCount();
        }
        if (totalDone == 0) return Double.NaN;
        double mean = Math.exp(-oc.r * oc.T) * (sum / totalDone);
        return mean;
    }

    // worker for threads
    private static class Worker implements Runnable {
        private final BlackScholesEngine.OptionContract oc;
        private final int nsim;
        private final long seed;
        private double sum = 0.0;
        private int count = 0;

        Worker(BlackScholesEngine.OptionContract oc, int nsim, long seed) {
            this.oc = oc;
            this.nsim = nsim;
            this.seed = seed;
        }

        public void run() {
            Random rng = (seed == 0) ? new Random() : new Random(seed);
            for (int i = 0; i < nsim; i++) {
                double z = Math.sqrt(-2.0 * Math.log(rng.nextDouble())) * Math.cos(2.0 * Math.PI * rng.nextDouble());
                double ST = oc.S * Math.exp((oc.r - oc.q - 0.5 * oc.sigma * oc.sigma) * oc.T + oc.sigma * Math.sqrt(oc.T) * z);
                double payoff = 0.0;
                switch (oc.type) {
                    case EUROPEAN_CALL -> payoff = Math.max(0.0, ST - oc.K);
                    case EUROPEAN_PUT -> payoff = Math.max(0.0, oc.K - ST);
                    case BINARY_CALL -> payoff = (ST > oc.K) ? 1.0 : 0.0;
                    case DIGITAL_PUT -> payoff = (ST < oc.K) ? 1.0 : 0.0;
                }
                sum += payoff;
                count++;
            }
        }

        public double getSum() { return sum; }
        public int getCount() { return count; }
    }

    // print header
    private static void printHeader(String title) {
        System.out.println("\n=== " + title + " ===");
    }

    // read/save session history (simple)
    private static void saveHistory() {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(HISTORY_FILE, true))) {
            for (String s : sessionLog) bw.write(s + System.lineSeparator());
            sessionLog.clear();
        } catch (IOException e) {
            printlnError("Could not save history: " + e.getMessage());
        }
    }

    private static void loadHistory() {
        File f = new File(HISTORY_FILE);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int loaded = 0;
            while ((line = br.readLine()) != null) {
                sessionLog.add(line);
                loaded++;
                if (loaded >= 200) break;
            }
            if (loaded > 0) printlnInfo("Loaded " + loaded + " history entries");
        } catch (IOException e) {
            printlnError("Could not load history: " + e.getMessage());
        }
    }

    // helper prints
    private static void printlnInfo(String s) { System.out.println(ANSI_GREEN + s + ANSI_RESET); }
    private static void printlnWarn(String s) { System.out.println(ANSI_YELLOW + s + ANSI_RESET); }
    private static void printlnError(String s) { System.out.println(ANSI_RED + s + ANSI_RESET); }
    private static void println(String s) { System.out.println(s); }

    // small trace to sessionLog and file
    private static void trace(String msg) {
        String ts = LocalDateTime.now().format(TS_FMT);
        String entry = ts + " - " + msg;
        sessionLog.add(entry);
        try { engine.appendLog(MAIN_LOG_FILE, entry); } catch (Exception ignored) {}
    }

    // persist log helper wrapper
    private static void persistLog(String entry, boolean immediateWrite) {
        sessionLog.add(entry);
        if (immediateWrite) {
            try { engine.appendLog(MAIN_LOG_FILE, entry); } catch (Exception e) { printlnError("Log write fail: " + e.getMessage()); }
        }
    }

    // ---------- Input utility nested class ----------
    private static final class InputUtil {

        private InputUtil() {}

        static int readInt(String prompt, int lo, int hi) {
            while (true) {
                System.out.print(prompt);
                String token = SC.next();
                try {
                    int v = Integer.parseInt(token);
                    if (v < lo || v > hi) { printlnWarn("Out of range"); continue; }
                    return v;
                } catch (NumberFormatException e) {
                    printlnWarn("Invalid integer");
                }
            }
        }

        static double readDouble(String prompt, double lo, double hi) {
            while (true) {
                System.out.print(prompt);
                String token = SC.next();
                try {
                    double v = Double.parseDouble(token);
                    if (v < lo || v > hi) { printlnWarn("Out of range"); continue; }
                    return v;
                } catch (NumberFormatException e) {
                    printlnWarn("Invalid number");
                }
            }
        }

        static String readString(String prompt) {
            System.out.print(prompt);
            return SC.nextLine();
        }
    }

    // ---------- Minimal unit-style test methods (manual) ----------
    // These are helper methods to generate test data; not full unit tests.
    private static void runSanityChecks() {
        printHeader("Sanity Checks");
        try {
            BlackScholesEngine.OptionContract oc = new BlackScholesEngine.OptionContract(OptionType.EUROPEAN_CALL, 100, 100, 0.05, 0.2, 1.0, 0.0);
            double p = engine.analyticPrice(oc);
            System.out.println("Call price (expected ~10) -> " + DF6.format(p));
            double iv = engine.impliedVol(oc, p, 1e-6, 200);
            System.out.println("Recovered vol -> " + DF6.format(iv));
            engine.appendLog(MAIN_LOG_FILE, "Sanity check passed");
        } catch (Exception e) {
            printlnError("Sanity check failed: " + e.getMessage());
        }
    }

    // ---------- End of class ----------
}
