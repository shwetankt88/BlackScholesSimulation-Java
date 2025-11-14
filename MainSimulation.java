import bs.BlackScholesEngine;
import bs.OptionType;
import java.util.*;

/**
 * MainSimulation: menu-driven program that uses BlackScholesEngine module.
 *
 * Place MainSimulation.java in project root (same level as folder "bs").
 */
public class MainSimulation {

    private static final Scanner sc = new Scanner(System.in);
    private static final BlackScholesEngine engine = new BlackScholesEngine();

    public static void main(String[] args) {
        System.out.println("Welcome to the Black-Scholes Simulation Suite");
        boolean run = true;
        while (run) {
            printMenu();
            int choice = readInt("Choice (1-10): ", 1, 10);
            switch (choice) {
                case 1 -> analyticFlow();
                case 2 -> monteCarloFlow();
                case 3 -> impliedVolFlow();
                case 4 -> batchCsvFlow();
                case 5 -> saveExampleCsv();
                case 6 -> asciiHistogramDemo();
                case 7 -> greeksFlow();
                case 8 -> selfTest();
                case 9 -> showLog();
                case 10 -> { run = false; System.out.println("Exiting..."); }
                default -> System.out.println("Unknown option");
            }
        }
        sc.close();
    }

    private static void printMenu() {
        System.out.println("\n--- MENU ---");
        System.out.println("1) Analytic Black-Scholes price");
        System.out.println("2) Monte Carlo pricing (standard/antithetic)");
        System.out.println("3) Implied volatility (BS)");
        System.out.println("4) Batch CSV: read options.csv -> results.csv");
        System.out.println("5) Save example CSV template");
        System.out.println("6) ASCII histogram demo of terminal prices (MC)");
        System.out.println("7) Show Greeks for an option");
        System.out.println("8) Run self-test demo");
        System.out.println("9) Show simulation log (last lines)");
        System.out.println("10) Exit");
    }

    private static int readInt(String prompt, int lo, int hi) {
        while (true) {
            System.out.print(prompt);
            try {
                int v = Integer.parseInt(sc.next());
                if (v < lo || v > hi) System.out.println("Out of range.");
                else return v;
            } catch (Exception e) {
                System.out.println("Invalid integer.");
            }
        }
    }

    private static double readDouble(String prompt, double lo, double hi) {
        while (true) {
            System.out.print(prompt);
            try {
                double v = Double.parseDouble(sc.next());
                if (v < lo || v > hi) System.out.println("Out of range.");
                else return v;
            } catch (Exception e) {
                System.out.println("Invalid number.");
            }
        }
    }

    private static BlackScholesEngine.OptionContract readOptionFromUser() {
        System.out.println("Choose option type: 1) EUROPEAN_CALL 2) EUROPEAN_PUT 3) BINARY_CALL 4) DIGITAL_PUT");
        int t = readInt("Type (1-4): ", 1, 4);
        OptionType type = switch (t) {
            case 1 -> OptionType.EUROPEAN_CALL;
            case 2 -> OptionType.EUROPEAN_PUT;
            case 3 -> OptionType.BINARY_CALL;
            default -> OptionType.DIGITAL_PUT;
        };
        double S = readDouble("Spot S: ", 1e-6, 1e12);
        double K = readDouble("Strike K: ", 1e-6, 1e12);
        double r = readDouble("Risk-free rate r (e.g. 0.05): ", -1.0, 5.0);
        double sigma = readDouble("Volatility sigma (e.g. 0.2): ", 1e-6, 10.0);
        double T = readDouble("Time to maturity T (years): ", 0.0, 100.0);
        double q = readDouble("Dividend yield q (e.g. 0): ", -1.0, 5.0);
        return new BlackScholesEngine.OptionContract(type, S, K, r, sigma, T, q);
    }

    // Analytic flow
    private static void analyticFlow() {
        var oc = readOptionFromUser();
        double price = engine.analyticPrice(oc);
        System.out.println("Analytic price = " + price);
        engine.appendLog("bs_sim_log.txt", "Analytic " + oc + " -> " + price);
    }

    // Monte Carlo flow
    private static void monteCarloFlow() {
        var oc = readOptionFromUser();
        int nsim = (int) readDouble("Simulations (e.g. 10000): ", 100, 5_000_000);
        long seed = (long) readDouble("Seed (0 random): ", 0, Long.MAX_VALUE);
        int mode = readInt("Method: 1=Standard 2=Antithetic: ", 1, 2);
        boolean antithetic = (mode == 2);
        System.out.println("Running Monte Carlo...");
        double price = engine.monteCarloPrice(oc, nsim, seed, antithetic);
        System.out.println("MC price = " + price);
        engine.appendLog("bs_sim_log.txt", "MC " + oc + " nsim=" + nsim + " -> " + price);
    }

    // Implied vol flow
    private static void impliedVolFlow() {
        var oc = readOptionFromUser();
        double market = readDouble("Market price: ", 0.0, Double.MAX_VALUE);
        double iv = engine.impliedVol(oc, market, 1e-6, 300);
        if (iv < 0) System.out.println("Could not find implied vol (try different guess/bounds).");
        else System.out.println("Implied vol ≈ " + iv);
        engine.appendLog("bs_sim_log.txt", "ImpliedVol " + oc + " market=" + market + " -> iv=" + iv);
    }

    // Batch CSV flow
    private static void batchCsvFlow() {
        System.out.print("Input CSV filename (default: options.csv): ");
        String in = sc.next().trim();
        if (in.isEmpty()) in = "options.csv";
        List<BlackScholesEngine.OptionContract> list = engine.readOptionsCsv(in);
        if (list.isEmpty()) {
            System.out.println("No options read from " + in);
            return;
        }
        List<String> out = new ArrayList<>();
        out.add("type,S,K,r,sigma,T,q,price");
        for (var oc : list) {
            double p = engine.analyticPrice(oc);
            out.add(String.format(Locale.US, "%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                    oc.type, oc.S, oc.K, oc.r, oc.sigma, oc.T, oc.q, p));
            System.out.println(oc + " -> " + p);
        }
        System.out.print("Output filename (default: results.csv): ");
        String outfn = sc.next().trim();
        if (outfn.isEmpty()) outfn = "results.csv";
        engine.writeResultsCsv(outfn, out);
        engine.appendLog("bs_sim_log.txt", "Batch processed " + list.size() + " options -> " + outfn);
    }

    private static void saveExampleCsv() {
        List<String> rows = List.of(
                "# type,S,K,r,sigma,T,q",
                "EUROPEAN_CALL,100,100,0.05,0.2,1.0,0.0",
                "EUROPEAN_PUT,100,95,0.05,0.25,0.5,0.0"
        );
        engine.writeResultsCsv("options_example.csv", rows);
        System.out.println("Saved options_example.csv");
    }

    private static void asciiHistogramDemo() {
        var oc = readOptionFromUser();
        int nsim = (int) readDouble("Simulations (e.g. 20000): ", 100, 1_000_000);
        long seed = (long) readDouble("Seed (0 random): ", 0, Long.MAX_VALUE);
        Random rng = (seed == 0) ? new Random() : new Random(seed);
        double[] samples = new double[nsim];
        for (int i = 0; i < nsim; i++) {
            double z = Math.sqrt(-2.0 * Math.log(rng.nextDouble())) * Math.cos(2.0 * Math.PI * rng.nextDouble());
            double ST = oc.S * Math.exp((oc.r - oc.q - 0.5 * oc.sigma * oc.sigma) * oc.T + oc.sigma * Math.sqrt(oc.T) * z);
            samples[i] = ST;
        }
        engine.asciiHistogram(samples, 30);
        engine.appendLog("bs_sim_log.txt", "Ascii histogram for " + oc + " nsim=" + nsim);
    }

    private static void greeksFlow() {
        var oc = readOptionFromUser();
        double price = engine.analyticPrice(oc);
        System.out.println("Price: " + price);
        System.out.println("Greeks: " + engine.analyticGreeks(oc));
    }

    private static void selfTest() {
        System.out.println("Running self-test...");
        engine.selfTest();
        engine.appendLog("bs_sim_log.txt", "Self-test executed");
    }

    private static void showLog() {
        System.out.println("---- Last log lines (bs_sim_log.txt) ----");
        try (Scanner f = new Scanner(new java.io.File("bs_sim_log.txt"))) {
            List<String> lines = new ArrayList<>();
            while (f.hasNextLine()) lines.add(f.nextLine());
            int start = Math.max(0, lines.size() - 20);
            for (int i = start; i < lines.size(); i++) System.out.println(lines.get(i));
        } catch (Exception e) {
            System.out.println("(No log file yet)");
        }
    }
}
