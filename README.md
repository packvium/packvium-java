# Packvium for Java

Deterministic 3D cartonization and container packing in Java, with exact integer geometry
and no runtime dependencies.

Packvium decides which boxes, cartons, pallets or trailers a set of items goes into, and
where each item sits inside them. You describe the items (size, weight, how they may be
turned, what may sit on them) and the containers (inner size, payload, cost), and it
returns placements, the items it could not place and why, and a score you can compare.
Around that core sit the pieces a warehouse or shipping system needs: shipping quotes and
eligibility policy, execution plans an operator can follow, operator locks, and tools to
prove that a change to your carton set is an improvement before you publish it.

This is the Java port of Packvium. It reads the same JSON requests as the Python and PHP
reference engines, and its tests hold it to their answers byte for byte on the shared
conformance corpus.

> **Version 1.2.0.** This release corresponds to Packvium 1.2.0 in the other languages.
> Field names, status codes and the objective vector stay the same for all of 1.x, so a
> later 1.x release can replace any earlier one without code changes.

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Packing with domain objects](#packing-with-domain-objects)
- [The JSON request API](#the-json-request-api)
- [Constraints and objectives](#constraints-and-objectives)
- [Nested packing](#nested-packing)
- [Commerce: quotes, policy and catalogs](#commerce-quotes-policy-and-catalogs)
- [Execution plans](#execution-plans)
- [Operator locks](#operator-locks)
- [Scenario comparison, recommendations and holdout replay](#scenario-comparison-recommendations-and-holdout-replay)
- [Examples](#examples)
- [Command line](#command-line)
- [Compatibility](#compatibility)
- [Determinism](#determinism)
- [Differences from the other engines](#differences-from-the-other-engines)
- [Versioning](#versioning)
- [The Packvium family](#the-packvium-family)

## Install

Maven:

```xml
<dependency>
    <groupId>com.packvium</groupId>
    <artifactId>packvium</artifactId>
    <version>1.2.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("com.packvium:packvium:1.2.0")
```

Gradle (Groovy DSL):

```groovy
implementation 'com.packvium:packvium:1.2.0'
```

The jar has no dependencies of its own.

## Quick start

```java
import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.result.PackingResult;
import java.util.List;

PackingResult result = new Packer(PackingConfig.balanced()).pack(
        List.of(Item.builder("book", Dimensions.mm("210", "140", "30")).quantity(4).build()),
        List.of(Container.builder("box", Dimensions.mm("400", "300", "250")).build()));

System.out.println(result.status().wireName());   // feasible
for (PackedContainer container : result.containers()) {
    for (Placement placement : container.placements()) {
        // book#1 Point[x=0, y=0, z=0] HWL -- coordinates are exact ticks (1/16000 mm)
        System.out.println(placement.instance().id() + " " + placement.position() + " " + placement.rotation());
    }
}
```

## Packing with domain objects

Items and containers are immutable records built with a builder. Lengths and weights are
parsed from strings, so `"12 3/8"` inches or `"450 g"` are stored exactly, without
rounding to millimetres:

```java
import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.UnpackedItem;
import com.packvium.explain.Explain;
import com.packvium.result.PackingResult;
import com.packvium.unit.Weight;
import java.util.List;

List<Item> items = List.of(
        Item.builder("book", Dimensions.mm("210", "140", "30"))
                .weight(Weight.parse("450 g")).quantity(4).build(),
        Item.builder("mug", Dimensions.inches("4", "4", "5"))
                .weight(Weight.parse("12 oz")).quantity(2).keepUpright(true).build());
List<Container> containers = List.of(
        Container.builder("box-m", Dimensions.mm("400", "300", "250"))
                .maxPayload(Weight.parse("20 kg")).costMinor(180).build(),
        Container.builder("box-l", Dimensions.mm("500", "400", "350"))
                .maxPayload(Weight.parse("30 kg")).costMinor(250).build());

// The argument is a time limit in milliseconds. Treat it as a safety fuse, not a target.
PackingResult result = new Packer(PackingConfig.balanced(60_000)).pack(items, containers);

System.out.println(result.packedItemCount() + " packed, score " + result.score());
for (UnpackedItem unpacked : result.unpacked()) {
    // Every item left behind carries a reason code; Explain turns it into a sentence.
    System.out.println(unpacked.instance().item().id() + ": " + Explain.unpackedItem(unpacked));
}
```

Every result has passed an independent validator that rechecks the placements against
every rule. `result.score()` is a vector of exact integers compared lexicographically,
lower is better; its first entry is always the number of items left unplaced.

## The JSON request API

The request and result formats are the ones every Packvium engine uses, so a request
written for Python or PHP works here unchanged. `JsonReader` and `JsonWriter` are the
port's own dependency-free JSON codec:

```java
import com.packvium.serialization.JsonReader;
import com.packvium.serialization.JsonWriter;
import com.packvium.serialization.RequestPacker;
import java.util.Map;

Object request = JsonReader.parse("""
        {"configuration": {"solver_profile": "balanced", "time_limit_ms": 60000},
         "items": [{"id": "box", "quantity": 8,
                    "dimensions": {"length": "50", "width": "50", "height": "50"}}],
         "containers": [{"id": "carton",
                         "inner_dimensions": {"length": "100", "width": "100", "height": "100"}}]}
        """);
Map<String, Object> result = RequestPacker.pack(request);
System.out.println(result.get("status"));         // feasible
System.out.println(JsonWriter.write(result));      // the full result document
```

A malformed request throws `IllegalArgumentException` with the same message the other
engines give. A field the engine reserves but does not implement yet, such as
`container.pallet_overhang_limit`, is refused with `UnsupportedFeatureException`
(`unsupported_feature`) rather than silently ignored. To get the JSON form of a result you
built with domain objects, use `new ResultEncoder("mm", "g").encode(result)`.

## Constraints and objectives

Every per-item and per-container rule is a builder setter. A few of them:

```java
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.unit.Weight;
import java.util.List;

Item panel = Item.builder("glass-panel", Dimensions.mm("400", "300", "40"))
        .weight(Weight.parse("8 kg"))
        .mustBeOnFloor(true)                      // only on the container floor
        .maxTopLoad(Weight.parse("2 kg"))         // at most 2 kg resting directly on it
        .build();
Item cake = Item.builder("cake", Dimensions.mm("250", "250", "150")).stackable(false).build();
Item bleach = Item.builder("bleach", Dimensions.mm("120", "120", "300"))
        .tags(List.of("hazmat"))
        .incompatibleTags(List.of("food"))        // never shares a container with a food item
        .build();
Container crate = Container.builder("crate", Dimensions.mm("600", "500", "500"))
        .tareWeight(Weight.parse("3 kg"))
        .maxPayload(Weight.parse("25 kg"))
        .costMinor(400)
        .build();
```

Also available: allowed rotations, keep-upright, minimum support ratio, maximum stacked
items, clearance, obstacles, tag limits, axle loads, delivery stops (`stopIndex`) with the
doors a container is unloaded through (`accessDirections`), convex-hull shapes and
compressible items. [`examples/Constraints.java`](examples/Constraints.java) and
[`examples/Shapes.java`](examples/Shapes.java) show them at work.

The objective decides which of several valid packings wins. There are six:
`default`, `lowest_cost`, `shipping_cost`, `lowest_landed_cost`, `open_dimension_height`
and `maximum_value`. Choose one, and a solver profile (`FAST`, `BALANCED`, `QUALITY`,
`EXACT_SMALL`), on the configuration:

```java
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;

PackingConfig config = PackingConfig.builder(SolverProfile.BALANCED)
        .objective("lowest_cost")
        .timeLimitMs(60_000)
        .build();
```

No objective will leave an item behind to save money: the unplaced count always comes
first. [`examples/Objectives.java`](examples/Objectives.java) builds a scene for each one
where it chooses differently.

## Nested packing

Units go into cartons and cartons onto pallets, in one call. Each level is packed in turn
and its containers, at their outer dimensions, become the next level's items:

```java
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.nested.NestedPacker;
import com.packvium.nested.NestedPackingResult;
import com.packvium.nested.PackingLevel;
import com.packvium.unit.Weight;
import java.util.List;
import java.util.Optional;

List<PackingLevel> levels = List.of(
        new PackingLevel("carton", List.of(
                Container.builder("box-s", Dimensions.mm("300", "300", "300"))
                        .outerDimensions(Dimensions.mm("310", "310", "310")).build()),
                Optional.of(PackingConfig.fast(60_000))),
        new PackingLevel("pallet", List.of(
                Container.builder("euro", Dimensions.mm("1200", "800", "1400"))
                        .maxPayload(Weight.parse("700 kg")).build())));
NestedPackingResult result = new NestedPacker().pack(
        List.of(Item.builder("mug", Dimensions.mm("120", "120", "100")).quantity(24).build()), levels);
// result.levels() holds one PackingResult per level, in the order given.
```

## Commerce: quotes, policy and catalogs

`com.packvium.commerce.Api` prices a shipment against a versioned tariff, evaluates an
eligibility policy and resolves catalog versions. Each call is a deterministic function of
the document you pass in: no clock, no network, no hidden state.

```java
import com.packvium.commerce.Api;
import java.util.List;
import java.util.Map;

// `document` holds your tariffs, policy rules and catalogs; Commerce.java shows a complete one.
Map<String, Object> quote = Api.quote(document, Map.of(
        "carrier_id", "acme", "service_id", "ground",
        "tariff_version", 1L,                  // replay against exactly this version
        "zone", "zone-a", "actual_weight_g", 1200L, "volume_mm3", 6000000L,
        "requested_accessorials", List.of("liftgate")));
Map<String, Object> decision = Api.evaluatePolicy(document,
        Map.of("scope", "hazmat", "context", Map.of("un_class", "1.4"), "as_of", 0L));
String stored = Api.canonicalJson(quote);      // the byte-stable form to store and compare
```

A request the model cannot answer (an unpriced zone, say) is a result with a status and a
code from a closed set, not an exception. A malformed one throws `CommerceInputException`.

## Execution plans

A packing result says where every box goes. An execution plan turns it into a work order:
the solver's facts kept apart from display text, every sentence citing the result fields
it was built from, and a loading order only when you supply one. The plan calls no solver
and no validator, and `Plan.canonicalJson` is byte for byte what the other engines produce
for the same result.

```java
import com.packvium.execution.Plan;
import com.packvium.serialization.RequestPacker;
import java.util.List;
import java.util.Map;

Map<String, Object> result = RequestPacker.pack(request);
Map<String, Object> plan = Plan.build(request, result);
// Without a loading order each container's steps say `order: unavailable` rather than guessing.
Map<String, Object> ordered = Plan.build(request, result, Map.of(0, List.of(4, 3, 2, 1, 0)));
String canonical = Plan.canonicalJson(plan);
```

A result the adapter cannot describe, or a loading order that is not a permutation of the
container's placements, throws `ExecutionPlanException`.

## Operator locks

An operator standing at the dock can pin a box: *this item stays here*. A lock is read
from the plan's own placement reference and applied as an ordinary placement constraint,
so the re-solve runs through the same solvers and the same independent validator. It can
forbid alternatives to itself and nothing else; it can never produce a placement the
engine would otherwise refuse.

```java
import com.packvium.execution.Plan;
import com.packvium.locks.LockSetError;
import com.packvium.locks.LockedResolve;
import com.packvium.locks.Locks;
import com.packvium.locks.PlacementLock;
import java.util.List;
import java.util.Map;

List<PlacementLock> locks = Locks.locksFromPlan(plan, 0, List.of("printer"));
LockedResolve resolved = Locks.resolveWithLocks(request, locks);
if (resolved.preserved()) {
    // A second result beside the approved one, which is never changed.
    Map<String, Object> proposal = resolved.result();
} else {
    List<PlacementLock> notHonoured = resolved.missing();
}
```

There are two different ways for a lock to fail, and they are reported differently:

- A lock the solve cannot honour (it would float the item, overload what is under it, or
  block an earlier delivery stop) is an **answer**: `preserved()` is false, the lock is
  named in `missing()`, and the result is still a valid packing.
- A lock set that contradicts itself (two locks claiming overlapping space, the same
  placement locked twice, an item type the request does not contain) is refused with
  `LockSetError` before anything is solved.

A lock is addressed by container index, item type, exact position ticks and orientation,
never by item id. Locks have no field in the JSON request; they are a library feature.
Your own constraints still apply alongside a lock: `Locks.resolveWithLocks(request, locks,
extensions)` keeps every constraint in `extensions`, and `RequestPacker.pack(request,
extensions)` adds caller constraints to the request's own policy rules rather than
replacing them.

## Scenario comparison, recommendations and holdout replay

These answer the question that comes after "can we pack it?": *is the new carton set
actually better, and how would we know?* None of them returns a single blended number.
They work over metrics your own evaluator supplies, so they run no solver themselves.

**Compare two scenarios order by order.** Each order gets its own Pareto report: a winner
when one arm is no worse on every axis and better on one, and no winner when the trade-off
is real.

```java
import com.packvium.pareto.ProfileReport;
import com.packvium.simulation.OrderRunResult;
import com.packvium.simulation.ScenarioResult;
import com.packvium.simulation.ScenarioVersionPin;
import com.packvium.simulation.Simulation;
import java.util.List;
import java.util.Map;

// A pin names the catalog version each arm ran against, so the comparison replays exactly.
ScenarioVersionPin current = ScenarioVersionPin.ofCatalogVersion(1);
ScenarioVersionPin proposed = ScenarioVersionPin.ofCatalogVersion(2);
List<String> orders = List.of("order-1", "order-2");

// In your code each evaluator packs the order under the pinned catalog and measures it.
ScenarioResult baseline = Simulation.runScenario("current-cartons", current, orders,
        (orderId, pin) -> OrderRunResult.succeeded(orderId, Map.of("cost_minor", 1240.0, "utilisation", 0.71)));
ScenarioResult treatment = Simulation.runScenario("proposed-cartons", proposed, orders,
        (orderId, pin) -> OrderRunResult.succeeded(orderId, Map.of("cost_minor", 1120.0, "utilisation", 0.76)));

// Every axis states its direction; there is no default.
Map<String, Boolean> higherIsBetter = Map.of("cost_minor", false, "utilisation", true);
for (ProfileReport report : Simulation.compareScenarios(baseline, treatment, higherIsBetter)) {
    System.out.println(report.profile() + ": " + report.winner().orElse("no winner"));
}
```

**Turn the comparison into a proposal, or refuse to.** `Recommendations.propose` averages
only the orders both arms packed, and returns an empty `Optional` when the paired cohort is
smaller, or a smaller share of the corpus, than the thresholds you set. Publishing is a
separate, explicit call through the real append-only registry:

```java
import com.packvium.commerce.catalog.CatalogRegistry;
import com.packvium.commerce.catalog.Snapshot;
import com.packvium.recommendations.ApprovalRecord;
import com.packvium.recommendations.Recommendation;
import com.packvium.recommendations.Recommendations;
import java.util.List;
import java.util.Optional;

Optional<Recommendation> proposal = Recommendations.propose("rec-cartons-2024",
        "adopt the proposed carton set", baseline, treatment,
        List.of("no placement the validator would reject"), "republish catalog version 1",
        2,        // at least two paired orders
        0.75);    // covering at least 75% of the corpus
if (proposal.isPresent()) {
    ApprovalRecord approval = Recommendations.approveCatalog(proposal.get(),
            new CatalogRegistry("cartons"), new Snapshot(), 1_000, 2_000);
}
```

**Replay it against history it has never seen.** An `OutcomeLedger` records what really
happened to each shipment, append-only, with a closed vocabulary of fields per event type.
`Holdout.evaluateOnHistory` splits the decisions by time, refuses a recommendation that
cites a held-out decision, runs your independent validator on both arms before comparing
anything, and never credits the treatment with avoiding damage that happened under the
carton that actually shipped:

```java
import com.packvium.holdout.Holdout;
import com.packvium.holdout.HoldoutEvaluation;
import com.packvium.holdout.OrderEvaluationArtifact;
import com.packvium.holdout.ValidationVerdict;
import com.packvium.outcomes.OutcomeEvent;
import com.packvium.outcomes.OutcomeEventType;
import com.packvium.outcomes.OutcomeLedger;
import com.packvium.simulation.OrderRunResult;
import java.util.List;
import java.util.Map;

OutcomeLedger ledger = new OutcomeLedger();
ledger.record(new OutcomeEvent("e1", "shipment-101", OutcomeEventType.ACTUAL_CARTON,
        Map.of("carton_id", "box-b", "catalog_version", 1L), 900));

HoldoutEvaluation evaluation = Holdout.evaluateOnHistory(proposal.get(), ledger,
        List.of("shipment-101"), current, proposed,
        // Re-pack one historical decision under the pinned catalog; hand the validator the packing.
        (decisionId, pin) -> new OrderEvaluationArtifact(
                OrderRunResult.succeeded(decisionId, pin.equals(current)
                        ? Map.of("cost_minor", 1400.0, "utilisation", 0.66)
                        : Map.of("cost_minor", 1210.0, "utilisation", 0.73)),
                Map.of("decision_id", decisionId), Map.of("pin", pin)),
        (packedRequest, packedResult) -> ValidationVerdict.accepted(),   // your own check
        higherIsBetter,
        500);     // split_at: decisions first recorded at or after this instant are held out
// Prints "1 improved, 0 regressed": the replayed treatment is cheaper and denser.
System.out.println(evaluation.improved() + " improved, " + evaluation.regressed() + " regressed");
```

[`examples/Intelligence.java`](examples/Intelligence.java) walks through all of this on a
five-order cohort, including an order only one arm could pack, a trade-off, a placement
the validator rejects and a refused self-citation.

## Examples

The [`examples/`](examples) directory has ten runnable files. Each can be read from top to
bottom and run with the JDK source launcher, with no project set up around it. The test
suite (`ExamplesTest`) runs all ten and compares what each prints with what the reference
example of the same name prints: the PHP one, except for `Execution.java` and
`Intelligence.java`, whose reference is the Python one.

| File | What it shows |
| --- | --- |
| [`Basic.java`](examples/Basic.java) | The smallest useful call: items go in and placements come out. |
| [`Objectives.java`](examples/Objectives.java) | All six objectives, on scenes where they choose differently. |
| [`Constraints.java`](examples/Constraints.java) | Upright-only, floor-only, top-load limits and tags, and how to read why an item was refused. |
| [`Units.java`](examples/Units.java) | Why there are no floats: fractional inches, exact ticks, and a refusal caused by a single tick. |
| [`Serialization.java`](examples/Serialization.java) | The JSON contract, and which mistakes are refused and which are ignored. |
| [`Shapes.java`](examples/Shapes.java) | Items that do not fill their box: wedges sharing a crate as `convex_hull`, and a cushion that compresses until the crush limit refuses it. |
| [`Nested.java`](examples/Nested.java) | Units go into cartons and cartons go onto a pallet, in one call. |
| [`Commerce.java`](examples/Commerce.java) | Rate a shipment, apply an eligibility rule and pin a catalog version. |
| [`Execution.java`](examples/Execution.java) | Turn a result into a work order, then let an operator pin a box and see both ways a lock can fail. |
| [`Intelligence.java`](examples/Intelligence.java) | Prove a carton change is better before publishing it: per-order Pareto comparison, a withheld proposal, holdout replay. |

Build the jar once, then run any example against it:

```bash
mvn -q package -DskipTests
java -cp target/packvium-1.2.0.jar examples/Objectives.java
```

## Command line

The jar is also a command-line program. It reads a JSON packing request on standard input
and writes the result JSON to standard output:

```bash
echo '{"items":[{"id":"box","quantity":8,"dimensions":{"length":"50","width":"50","height":"50"}}],
       "containers":[{"id":"carton","inner_dimensions":{"length":"100","width":"100","height":"100"}}]}' \
  | java -jar target/packvium-1.2.0.jar
```

When a request is refused, the program exits with status 1 and prints the reason on
standard error, worded as the other engines word it. When standard input is empty, it
exits with status 2.

## Compatibility

- **Java 17 or newer.** The jar is compiled with `--release 17`, and continuous
  integration runs the full test suite on JDK 17, 21 and 25 on Linux, macOS and Windows.
- **No runtime dependencies.** JUnit is a test-only dependency.
- **Thread safety.** Requests, configurations, results and plans are immutable and can be
  shared between threads. A `Packer` keeps no state between calls, your own extensions
  aside. The registries
  (`CatalogRegistry`, `PolicyRegistry`, `CarrierRegistry`) and `OutcomeLedger` are
  in-memory, append-only stores and are not synchronised.

## Determinism

The same input gives the same output, every time, on every machine. The multi-start search
draws from its own generator seeded by the request's `seed`, no hash order reaches a result,
and the only effect of the clock is the time limit you set: a solve that hits it stops early and says so in its result.
Give the limit generous headroom when you need an answer that does not depend on how busy
the machine is; the examples use 60 seconds for that reason. Commerce, execution plans,
scenario comparison and holdout replay read no clock at all.

Lengths are counted in ticks of 1/16000 mm and weights in ticks of 1/8 µg. Coordinates
are never floating-point numbers, so rounding cannot change where an item is placed.

## Differences from the other engines

Valid requests get identical results in every engine. The Java port differs in a few
places, and none of them shows up in the conformance corpus:

- Lengths and weights are stored as 64-bit ticks, as in PHP. If a value would overflow,
  the request is refused, whereas Python would keep calculating with larger integers.
- The parallel portfolio cannot be enabled from a request, so it is not included. Every
  start runs one after another, which is how the reference engine runs them.
- Operator locks, scenario comparison, recommendations, the outcome ledger and holdout
  replay follow the Python engine, the only other engine that has them. Where Python
  returns `None`, these return an empty `Optional`.

`container.pallet_overhang_limit` is reserved in the request format but not implemented by
any engine yet. A request that sets it is refused with `unsupported_feature`.

## Versioning

The Java port follows the Packvium release history one version at a time: Java 1.2.0
answers what every other Packvium 1.2.0 answers. A version is released only once the port
passes that version's shared conformance corpus. Within 1.x, the request and result
formats, status codes and objective vector stay compatible; what changed in each release
is in the [changelog](CHANGELOG.md).

## The Packvium family

There are packages for Python (`pip install packvium`), PHP
(`composer require packvium/packvium`), Rust, Node.js and the browser. They all accept
the same requests. Documentation, the constraint reference and benchmarks are at
[packvium.com](https://packvium.com).

## License

MIT.
