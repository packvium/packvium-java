import com.packvium.holdout.DecisionOutcome;
import com.packvium.holdout.Holdout;
import com.packvium.holdout.HoldoutEvaluation;
import com.packvium.holdout.OrderEvaluationArtifact;
import com.packvium.holdout.SupportingEvidenceInHoldoutError;
import com.packvium.holdout.ValidationVerdict;
import com.packvium.outcomes.OutcomeEvent;
import com.packvium.outcomes.OutcomeEventType;
import com.packvium.outcomes.OutcomeLedger;
import com.packvium.pareto.ProfileReport;
import com.packvium.recommendations.ExpectedDelta;
import com.packvium.recommendations.Recommendation;
import com.packvium.recommendations.Recommendations;
import com.packvium.simulation.OrderEvaluator;
import com.packvium.simulation.OrderRunResult;
import com.packvium.simulation.ScenarioResult;
import com.packvium.simulation.ScenarioVersionPin;
import com.packvium.simulation.Simulation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prove a carton change is better before you publish it.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.7.0.jar examples/Intelligence.java
 * </pre>
 *
 * <p>Every other example asks the engine to pack one shipment. This one asks a different question,
 * the one that comes up when you already pack well and want to change something: <em>is the new
 * carton set actually an improvement, and how would I know?</em>
 *
 * <p>Four calls answer it, and none of them returns a single blended number. A score that folds
 * cost and utilisation together can name a "winner" that is worse on the axis you actually care
 * about, so what you get back is a per-order Pareto report: which orders improved, which
 * regressed, and which genuinely traded one axis for another.
 *
 * <p>Nothing here reads the clock or the network. The scenarios are pinned to catalog versions you
 * supply, so the same comparison replays to the same answer next year.
 */
public final class Intelligence {
    private static final String RULE = "=".repeat(78);

    /** One order measured under both carton sets; a null treatment means the proposed set cannot pack it. */
    private record Measured(double baselineCost, double baselineUtilisation, Double treatmentCost,
            double treatmentUtilisation) {
    }

    // Two carton sets over the same five orders. You would measure these by packing each order
    // twice; they are written out here so the example has nothing to hide.
    //
    // `cost_minor` is in cents and lower is better. `utilisation` is filled volume over container
    // volume and higher is better. Order 5 is the interesting one: the proposed cartons cannot pack
    // it at all.
    private static final Map<String, Measured> MEASURED = orders(
            "order-1", new Measured(1240, 0.71, 1120.0, 0.76),
            "order-2", new Measured(980, 0.68, 910.0, 0.72),
            "order-3", new Measured(1500, 0.80, 1600.0, 0.83),
            "order-4", new Measured(2100, 0.64, 1890.0, 0.69),
            "order-5", new Measured(1350, 0.70, null, 0));
    private static final List<String> ORDER_IDS = List.copyOf(MEASURED.keySet());

    // Both directions are stated explicitly. There is no default, because guessing that runtime or
    // cost is "higher is better" would silently invert the whole report.
    private static final Map<String, Boolean> HIGHER_IS_BETTER = orders("cost_minor", false, "utilisation", true);

    // A pin is what makes a comparison replayable: it names the catalog version each arm was run
    // against rather than whatever is current when you happen to run it.
    private static final ScenarioVersionPin BASELINE_PIN = ScenarioVersionPin.ofCatalogVersion(1);
    private static final ScenarioVersionPin TREATMENT_PIN = ScenarioVersionPin.ofCatalogVersion(2);

    public static void main(String[] arguments) {
        section("1. Two scenarios, compared order by order");
        ScenarioResult baseline = Simulation.runScenario("current-cartons", BASELINE_PIN, ORDER_IDS, arm(true));
        ScenarioResult treatment = Simulation.runScenario("proposed-cartons", TREATMENT_PIN, ORDER_IDS, arm(false));
        for (ProfileReport report : Simulation.compareScenarios(baseline, treatment, HIGHER_IS_BETTER)) {
            if (report.winner().isPresent()) {
                System.out.printf("  %s: %s\n", report.profile(), report.winner().get());
            } else {
                System.out.printf("  %s: no winner — %s are both Pareto-optimal\n", report.profile(),
                        String.join(" and ", report.paretoOptimal()));
            }
        }
        System.out.print("\n");
        System.out.print("  order-3 has no winner because the trade-off is real: the proposed cartons cost\n");
        System.out.print("  more and pack denser. A blended score would have picked one and hidden that.\n");
        System.out.print("\n");
        System.out.print("  order-5 names `baseline` as winner for a duller reason — the proposed cartons\n");
        System.out.print("  produced no answer there, so there was only one candidate to be optimal. That\n");
        System.out.print("  is not a baseline win, and the next step is careful not to count it as one.\n");
        System.out.print("\n");

        section("2. A proposal, or an explicit refusal to make one");
        // The paired cohort is four orders out of five, so confidence is 0.8. Ask for more than the
        // evidence supports and you get an empty Optional rather than a proposal with a caveat.
        Optional<Recommendation> strict = propose(baseline, treatment, 0.9);
        System.out.printf("  minimum_confidence=0.9 -> %s\n", strict);
        System.out.print("  Four of five orders are comparable, so confidence is 0.80 and the bar is not\n");
        System.out.print("  met. `Optional.empty()` is the whole answer: no low-confidence proposal to weigh.\n");
        System.out.print("\n");

        Recommendation recommendation = propose(baseline, treatment, 0.75).orElseThrow();
        System.out.printf("  minimum_confidence=0.75 -> %s\n", recommendation.recommendationId());
        System.out.printf("  supported by: %s\n", String.join(", ", recommendation.supportingOrderIds()));
        System.out.printf("  confidence:   %s\n", fixed(recommendation.confidence(), 2));
        for (ExpectedDelta delta : recommendation.expectedDeltas()) {
            System.out.printf("    %-12s %8s -> %8s\n", delta.metric(), fixed(delta.baselineMean(), 3),
                    fixed(delta.treatmentMean(), 3));
        }
        System.out.print("\n");
        System.out.print("  order-5 is absent from the supporting orders. A delta is a paired comparison,\n");
        System.out.print("  so an order only one arm could pack contributes to neither mean.\n");
        System.out.print("\n");

        section("3. Replay against history the proposal has never seen");
        OutcomeLedger ledger = history();
        HoldoutEvaluation evaluation = Holdout.evaluateOnHistory(recommendation, ledger,
                List.of("order-1", "order-2", "shipment-101", "shipment-102", "shipment-103", "shipment-104"),
                BASELINE_PIN, TREATMENT_PIN, Intelligence::replay, Intelligence::validate, HIGHER_IS_BETTER, 500);

        System.out.print("  split_at=500 — required, with no default, because how much history is enough\n");
        System.out.print("  is a claim about your business that this library cannot make for you.\n");
        System.out.print("\n");
        System.out.printf("  training: %s\n", String.join(", ", evaluation.trainingDecisionIds()));
        System.out.printf("  held out: %s\n", String.join(", ", evaluation.holdoutDecisionIds()));
        System.out.print("\n");
        for (DecisionOutcome decision : evaluation.decisions()) {
            String note = "";
            if (!decision.treatmentCodes().isEmpty()) {
                note = "  (treatment rejected: " + String.join(", ", decision.treatmentCodes()) + ")";
            } else if (!decision.baselineRealisedRisk().isEmpty()) {
                note = "  (baseline actually suffered: " + String.join(", ", decision.baselineRealisedRisk()) + ")";
            }
            System.out.printf("    %-14s %s%s\n", decision.decisionId(), decision.verdict().value(), note);
        }
        System.out.print("\n");
        System.out.printf("  improved %d, regressed %d, traded off %d, unpackable %d\n", evaluation.improved(),
                evaluation.regressed(), evaluation.tradedOff(), evaluation.unpackable());
        System.out.print("\n");
        System.out.print("  shipment-104's note is negative evidence about the baseline, not a reason the\n");
        System.out.print("  treatment won. Damage, returns, repacks and operator overrides happened under\n");
        System.out.print("  the carton that actually shipped, so they are reported against that arm and\n");
        System.out.print("  never credited to the one that was never tried.\n");
        System.out.print("\n");
        System.out.print("  shipment-103 is the one to read twice. The proposed cartons packed it for 1050\n");
        System.out.print("  against 1600 and denser besides — and the independent validator rejected the\n");
        System.out.print("  placement, so it counts as a regression. There is no cost at which a packing\n");
        System.out.print("  your validator refuses becomes an improvement; the metrics are discarded, not\n");
        System.out.print("  discounted.\n");
        System.out.print("\n");

        section("4. A proposal cannot be scored on its own evidence");
        // Move the split earlier and order-1 -- which the recommendation cites -- lands in the held-out
        // set. That is marking your own homework, so it is refused rather than reported.
        try {
            Holdout.evaluateOnHistory(recommendation, ledger, List.of("order-1", "shipment-101"), BASELINE_PIN,
                    TREATMENT_PIN, Intelligence::replay, Intelligence::validate, HIGHER_IS_BETTER, 50);
        } catch (SupportingEvidenceInHoldoutError refusal) {
            System.out.printf("  refused: %s\n", refusal.getMessage());
        }
        System.out.print("\n");
        System.out.print("  The refusal is the feature. A holdout score is worth exactly as much as the\n");
        System.out.print("  separation between the evidence that produced the proposal and the evidence\n");
        System.out.print("  that tests it, and nothing else in the call can enforce that separation.\n");
    }

    /**
     * An evaluator for one arm. In your code this calls the packer; here it replays the
     * measurements above so the example teaches the comparison and not the packing.
     */
    private static OrderEvaluator arm(boolean isBaseline) {
        return (orderId, versionPin) -> {
            Measured measured = MEASURED.get(orderId);
            if (!isBaseline && measured.treatmentCost() == null) {
                return OrderRunResult.failed(orderId, "no carton in the proposed set fits this order");
            }
            return isBaseline ? metrics(orderId, measured.baselineCost(), measured.baselineUtilisation())
                    : metrics(orderId, measured.treatmentCost(), measured.treatmentUtilisation());
        };
    }

    private static Optional<Recommendation> propose(ScenarioResult baseline, ScenarioResult treatment,
            double minimumConfidence) {
        return Recommendations.propose("rec-cartons-2024", "adopt the proposed carton set", baseline, treatment,
                List.of("no placement the validator would reject"), "republish catalog version 1", 4,
                minimumConfidence);
    }

    /**
     * The ledger is append-only and records what actually happened to a shipment; its timestamps
     * are what a holdout split is made of. Each event type accepts a closed set of fields and
     * nothing else -- {@code damage} takes a reason and a severity, not a carton id -- which is what
     * lets a replay rely on what it finds there.
     */
    private static OutcomeLedger history() {
        OutcomeLedger ledger = new OutcomeLedger();
        List<OutcomeEvent> events = List.of(
                shipped("e1", "order-1", 100, "box-a"),
                shipped("e2", "order-2", 120, "box-a"),
                shipped("e3", "shipment-101", 900, "box-b"),
                shipped("e4", "shipment-102", 910, "box-a"),
                shipped("e5", "shipment-103", 920, "box-c"),
                shipped("e6", "shipment-104", 930, "box-b"),
                // A real bad outcome under the carton that actually shipped. It is reported against
                // the baseline and never credited to the treatment, because it happened.
                new OutcomeEvent("e7", "shipment-104", OutcomeEventType.DAMAGE,
                        Map.of("reason_code", "crushed_corner", "severity", "minor"), 940));
        events.forEach(ledger::record);
        return ledger;
    }

    private static OutcomeEvent shipped(String eventId, String decisionId, long recordedAt, String carton) {
        return new OutcomeEvent(eventId, decisionId, OutcomeEventType.ACTUAL_CARTON,
                Map.of("carton_id", carton, "catalog_version", 1L), recordedAt);
    }

    private static final Map<String, Measured> REPLAY = orders(
            "shipment-101", new Measured(1400, 0.66, 1210.0, 0.73),
            "shipment-102", new Measured(1150, 0.74, 1180.0, 0.77),
            // Cheaper under the proposed cartons -- and unstackable, which the validator catches.
            "shipment-103", new Measured(1600, 0.62, 1050.0, 0.81),
            "shipment-104", new Measured(990, 0.69, 940.0, 0.71));

    /** Re-pack one historical decision under whichever carton set the pin names. */
    private static OrderEvaluationArtifact replay(String decisionId, ScenarioVersionPin versionPin) {
        boolean isBaseline = versionPin.equals(BASELINE_PIN);
        Measured measured = REPLAY.get(decisionId);
        OrderRunResult run = isBaseline ? metrics(decisionId, measured.baselineCost(), measured.baselineUtilisation())
                : metrics(decisionId, measured.treatmentCost(), measured.treatmentUtilisation());
        // The request/result pair is what the validator is handed. It deliberately carries no
        // score, so nothing it decides can be influenced by how good the packing looked.
        return new OrderEvaluationArtifact(run, Map.of("decision_id", decisionId),
                Map.of("arm", isBaseline ? "baseline" : "treatment"));
    }

    /** Your own independent check, run over the packing rather than over its score. */
    private static ValidationVerdict validate(Object request, Object result) {
        boolean unstackable = "shipment-103".equals(((Map<?, ?>) request).get("decision_id"))
                && "treatment".equals(((Map<?, ?>) result).get("arm"));
        return unstackable ? ValidationVerdict.rejected(List.of("unsupported_item")) : ValidationVerdict.accepted();
    }

    private static OrderRunResult metrics(String orderId, double cost, double utilisation) {
        return OrderRunResult.succeeded(orderId, orders("cost_minor", cost, "utilisation", utilisation));
    }

    /** The exact binary value rounded half to even, as Python's format spec rounds a float. */
    private static String fixed(double value, int places) {
        return new BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** An insertion-ordered map from alternating keys and values. */
    @SuppressWarnings("unchecked") // Every call site passes values of the one type V.
    private static <V> Map<String, V> orders(Object... pairs) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], (V) pairs[index + 1]);
        }
        return map;
    }

    private static void section(String title) {
        System.out.print(RULE + "\n" + title + "\n" + RULE + "\n\n");
    }
}
