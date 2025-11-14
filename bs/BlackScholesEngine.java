package bs;

import java.io.*;
import java.util.*;

/**
 * BlackScholesEngine.java
 *
 * One-file package module that implements:
 *  - OptionContract model (public static inner class)
 *  - Analytic Black-Scholes pricing + Greeks
 *  - Monte Carlo pricer (standard & antithetic)
 *  - Implied volatility solver (Newton + bisection fallback)
 *  - CSV read/write and simple logging
 *  - ASCII histogram generator
 *
 * Public entry points are methods on the public BlackScholesEngine class.
 */
public class BlackScholesEngine {

    // -------------------------
    // Public nested model
    // -------------------------
    public static final class OptionContract {
        public final OptionType type;
        public final double S;
        public final double K;
        public final double r;
        public final double sigma;
        public final double T;
        public final double q;

        public OptionContract(OptionType type, double S, double K, double r, double sigma, double T, double q) {
            this.type = type;
            this.S = S;
            this.K = K;
            this.r = r;
            this.sigma = sigma;
            this.T = T;
            this.q = q;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s {S=%.4f, K=%.4f, r=%.4f, sigma=%.4f, T=%.4f, q=%.4f}",
                    type, S, K, r, sigma, T, q);
        }
    }

    // -------------------------
    // Utils (package-private static)
    // -------------------------
    static final class Util {
        private Util() {}

        static double normPdf(double x) {
            return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
        }

        // Normal CDF via Abramowitz & Stegun approximation
        static double normCdf(double x) {
            double sign = 1.0;
            if (x < 0) sign = -1.0;
            double absx = Math.abs(x) / Math.sqrt(2.0);

            double p  = 0.3275911;
            double a1 = 0.254829592;
            double a2 = -0.284496736;
            double a3 = 1.421413741;
            double a4 = -1.453152027;
            double a5 = 1.061405429;

            double t = 1.0 / (1.0 + p * absx);
            double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-absx * absx);
            double erf = sign * y;
            return 0.5 * (1.0 + erf);
        }

        static double d1(OptionContract o) {
            if (o.T <= 0 || o.sigma <= 0) return Double.NaN;
            return (Math.log(o.S / o.K) + (o.r - o.q + 0.5 * o.sigma * o.sigma) * o.T) / (o.sigma * Math.sqrt(o.T));
        }

        static double d2(OptionContract o) {
            return d1(o) - o.sigma * Math.sqrt(o.T);
        }
    }

    // -------------------------
    // Analytic Black-Scholes pricer + Greeks
    // -------------------------
    public double analyticPrice(OptionContract o) {
        if (o.T <= 0) {
            switch (o.type) {
                case EUROPEAN_CALL: return Math.max(0.0, o.S - o.K);
                case EUROPEAN_PUT:  return Math.max(0.0, o.K - o.S);
                case BINARY_CALL:   return (o.S > o.K) ? 1.0 : 0.0;
                case DIGITAL_PUT:   return (o.S < o.K) ? 1.0 : 0.0;
                default: throw new IllegalArgumentException("Unsupported option type");
            }
        }
        double D1 = Util.d1(o);
        double D2 = Util.d2(o);
        double discSpot = o.S * Math.exp(-o.q * o.T);
        double discStrike = o.K * Math.exp(-o.r * o.T);
        switch (o.type) {
            case EUROPEAN_CALL:
                return discSpot * Util.normCdf(D1) - discStrike * Util.normCdf(D2);
            case EUROPEAN_PUT:
                return discStrike * Util.normCdf(-D2) - discSpot * Util.normCdf(-D1);
            case BINARY_CALL:
                return Math.exp(-o.r * o.T) * Util.normCdf(D2);
            case DIGITAL_PUT:
                return Math.exp(-o.r * o.T) * Util.normCdf(-D2);
            default:
                throw new IllegalArgumentException("Unsupported option type");
        }
    }

    public String analyticGreeks(OptionContract o) {
        if (o.T <= 0) return "Greeks undefined at maturity";
        double D1 = Util.d1(o);
        double delta;
        if (o.type == OptionType.EUROPEAN_CALL) delta = Math.exp(-o.q * o.T) * Util.normCdf(D1);
        else if (o.type == OptionType.EUROPEAN_PUT) delta = Math.exp(-o.q * o.T) * (Util.normCdf(D1) - 1.0);
        else delta = 0.0;
        double gamma = Math.exp(-o.q * o.T) * Util.normPdf(D1) / (o.S * o.sigma * Math.sqrt(o.T));
        double vega  = o.S * Math.exp(-o.q * o.T) * Util.normPdf(D1) * Math.sqrt(o.T);
        double theta = computeTheta(o, D1);
        double rho   = computeRho(o);
        return String.format(Locale.US, "Delta=%.6f, Gamma=%.6f, Vega=%.6f, Theta=%.6f, Rho=%.6f",
                delta, gamma, vega, theta, rho);
    }

    private double computeTheta(OptionContract o, double D1) {
        double D2 = Util.d2(o);
        double first = - (o.S * Util.normPdf(D1) * o.sigma * Math.exp(-o.q * o.T)) / (2.0 * Math.sqrt(o.T));
        if (o.type == OptionType.EUROPEAN_CALL) {
            double term2 = o.q * o.S * Math.exp(-o.q * o.T) * Util.normCdf(D1);
            double term3 = o.r * o.K * Math.exp(-o.r * o.T) * Util.normCdf(D2);
            return first - term3 + term2;
        } else {
            double term2 = o.q * o.S * Math.exp(-o.q * o.T) * Util.normCdf(-D1);
            double term3 = o.r * o.K * Math.exp(-o.r * o.T) * Util.normCdf(-D2);
            return first + term3 - term2;
        }
    }

    private double computeRho(OptionContract o) {
        double D2 = Util.d2(o);
        if (o.type == OptionType.EUROPEAN_CALL)
            return o.K * o.T * Math.exp(-o.r * o.T) * Util.normCdf(D2);
        else
            return -o.K * o.T * Math.exp(-o.r * o.T) * Util.normCdf(-D2);
    }

    // -------------------------
    // Monte Carlo pricer (standard & antithetic)
    // -------------------------
    public double monteCarloPrice(OptionContract o, int nsim, long seed, boolean antithetic) {
        if (nsim <= 0) throw new IllegalArgumentException("nsim must be > 0");
        Random rng = (seed == 0) ? new Random() : new Random(seed);
        double sum = 0.0;
        if (!antithetic) {
            for (int i = 0; i < nsim; i++) {
                double z = gaussian(rng);
                double ST = o.S * Math.exp((o.r - o.q - 0.5 * o.sigma * o.sigma) * o.T + o.sigma * Math.sqrt(o.T) * z);
                sum += payoff(o, ST);
            }
            return Math.exp(-o.r * o.T) * (sum / nsim);
        } else {
            for (int i = 0; i < nsim; i++) {
                double z = gaussian(rng);
                double ST1 = o.S * Math.exp((o.r - o.q - 0.5 * o.sigma * o.sigma) * o.T + o.sigma * Math.sqrt(o.T) * z);
                double ST2 = o.S * Math.exp((o.r - o.q - 0.5 * o.sigma * o.sigma) * o.T - o.sigma * Math.sqrt(o.T) * z);
                sum += 0.5 * (payoff(o, ST1) + payoff(o, ST2));
            }
            return Math.exp(-o.r * o.T) * (sum / nsim);
        }
    }

    // simple Box-Muller gaussian
    private double gaussian(Random rng) {
        double u1 = rng.nextDouble();
        double u2 = rng.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    private double payoff(OptionContract o, double ST) {
        switch (o.type) {
            case EUROPEAN_CALL: return Math.max(0.0, ST - o.K);
            case EUROPEAN_PUT:  return Math.max(0.0, o.K - ST);
            case BINARY_CALL:   return (ST > o.K) ? 1.0 : 0.0;
            case DIGITAL_PUT:   return (ST < o.K) ? 1.0 : 0.0;
            default: return 0.0;
        }
    }

    // -------------------------
    // Implied volatility solver
    // -------------------------
    public double impliedVol(OptionContract o, double marketPrice, double tol, int maxIter) {
        if (marketPrice <= 0) return -1.0;

        // bracket
        double lo = 1e-6, hi = 5.0;
        double plo = priceWithSigma(o, lo) - marketPrice;
        double phi = priceWithSigma(o, hi) - marketPrice;
        if (plo * phi > 0) {
            // Newton starting from o.sigma or 0.2
            double sigma = Math.max(1e-3, Math.min(1.0, o.sigma > 0 ? o.sigma : 0.2));
            for (int i = 0; i < maxIter; i++) {
                OptionContract test = new OptionContract(o.type, o.S, o.K, o.r, sigma, o.T, o.q);
                double price = analyticPrice(test);
                double diff = price - marketPrice;
                if (Math.abs(diff) < tol) return sigma;
                double d1 = (Math.log(o.S / o.K) + (o.r - o.q + 0.5 * sigma * sigma) * o.T) / (sigma * Math.sqrt(o.T));
                double vega = o.S * Math.exp(-o.q * o.T) * Util.normPdf(d1) * Math.sqrt(o.T);
                if (vega < 1e-8) break;
                sigma = sigma - diff / vega;
                if (sigma <= 0) sigma = 1e-6;
            }
            return -1.0;
        }

        // bisection
        for (int i = 0; i < maxIter; i++) {
            double mid = 0.5 * (lo + hi);
            double pm = priceWithSigma(o, mid) - marketPrice;
            if (Math.abs(pm) < tol) return mid;
            if (plo * pm <= 0) {
                hi = mid; phi = pm;
            } else {
                lo = mid; plo = pm;
            }
        }
        return 0.5 * (lo + hi);
    }

    private double priceWithSigma(OptionContract o, double sigma) {
        OptionContract test = new OptionContract(o.type, o.S, o.K, o.r, sigma, o.T, o.q);
        return analyticPrice(test);
    }

    // -------------------------
    // CSV / File management
    // -------------------------
    public List<OptionContract> readOptionsCsv(String filename) {
        List<OptionContract> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            int lineno = 0;
            while ((line = br.readLine()) != null) {
                lineno++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length < 7) {
                    System.out.println("Skipping invalid csv line " + lineno + ": " + line);
                    continue;
                }
                OptionType t = OptionType.valueOf(parts[0].trim());
                double S = Double.parseDouble(parts[1].trim());
                double K = Double.parseDouble(parts[2].trim());
                double r = Double.parseDouble(parts[3].trim());
                double sigma = Double.parseDouble(parts[4].trim());
                double T = Double.parseDouble(parts[5].trim());
                double q = Double.parseDouble(parts[6].trim());
                list.add(new OptionContract(t, S, K, r, sigma, T, q));
            }
        } catch (IOException e) {
            System.out.println("CSV read error: " + e.getMessage());
        }
        return list;
    }

    public void writeResultsCsv(String filename, List<String> rows) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename, false))) {
            for (String row : rows) {
                bw.write(row);
                bw.newLine();
            }
            System.out.println("Saved results to " + filename);
        } catch (IOException e) {
            System.out.println("CSV write error: " + e.getMessage());
        }
    }

    public void appendLog(String filename, String msg) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename, true))) {
            bw.write(new Date() + " - " + msg);
            bw.newLine();
        } catch (IOException e) {
            System.out.println("Log error: " + e.getMessage());
        }
    }

    // -------------------------
    // ASCII histogram helper
    // -------------------------
    public void asciiHistogram(double[] samples, int bins) {
        if (samples == null || samples.length == 0) {
            System.out.println("No samples");
            return;
        }
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : samples) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (min == max) {
            System.out.println("All samples equal: " + min);
            return;
        }
        double width = (max - min) / bins;
        int[] counts = new int[bins];
        for (double v : samples) {
            int idx = (int) ((v - min) / width);
            if (idx < 0) idx = 0;
            if (idx >= bins) idx = bins - 1;
            counts[idx]++;
        }
        int maxCount = 1;
        for (int c : counts) if (c > maxCount) maxCount = c;
        for (int i = 0; i < bins; i++) {
            double left = min + i * width;
            double right = left + width;
            int bar = (int) Math.round(((double) counts[i] / maxCount) * 60);
            System.out.printf(Locale.US, "%.4f - %.4f | ", left, right);
            for (int j = 0; j < bar; j++) System.out.print('#');
            System.out.printf(" (%d)\n", counts[i]);
        }
    }

    // -------------------------
    // Small self-test demo
    // -------------------------
    public void selfTest() {
        OptionContract oc1 = new OptionContract(OptionType.EUROPEAN_CALL, 100, 100, 0.05, 0.2, 1.0, 0.0);
        OptionContract oc2 = new OptionContract(OptionType.EUROPEAN_PUT, 100, 110, 0.03, 0.25, 0.5, 0.0);
        System.out.println("OC1: " + oc1 + " => Price(analytic): " + analyticPrice(oc1));
        System.out.println("OC2: " + oc2 + " => Price(analytic): " + analyticPrice(oc2));
        double mc = monteCarloPrice(oc1, 5000, 42L, true);
        System.out.println("OC1 MC(Antithetic,5k) => " + mc);
        double iv = impliedVol(oc1, analyticPrice(oc1), 1e-6, 200);
        System.out.println("Implied vol of OC1 (should ≈ .2) => " + iv);
    }
}
