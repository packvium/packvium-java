# Changelog

What changed in `com.packvium:packvium` on Maven Central, release by release. The format
follows [Keep a Changelog](https://keepachangelog.com/1.1.0/) and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0]

Answers every request the way Packvium 1.2.0 does in the other languages, checked byte for
byte against the 1.2.0 conformance corpus.

### Added

- Execution plans: `com.packvium.execution.Plan.build(request, result)` turns a validated
  result into a plan an operator can follow. Solver facts are kept apart from display text,
  every sentence names the result fields it cites, a loading order appears only when you
  pass one in, and a runner-up is described by the first score axis where it differs.
  `Plan.canonicalJson` is the byte-stable form, identical to the other engines' for the same
  result. The plan calls no solver and no validator. A result it cannot describe throws
  `ExecutionPlanException`.
- Operator locks: `com.packvium.locks.Locks.locksFromPlan` reads a lock straight from a
  plan's placement reference, and `Locks.resolveWithLocks(request, locks)` re-solves with it
  applied as an ordinary placement constraint, beside the approved result rather than over
  it. A lock can only forbid alternatives to itself, so it never yields a placement the
  engine would otherwise refuse. A lock the solve cannot honour comes back in
  `LockedResolve.missing()` with a valid result; a lock set that contradicts itself
  (overlapping locks, a placement locked twice, an item type the request lacks) throws
  `LockSetError` before anything is solved.
- `RequestPacker.pack(request, extensions)`: caller extensions are added to the
  constraints the request's own policy compiles into, never substituted for them.
- Scenario comparison: `com.packvium.simulation.Simulation` runs an order corpus under a
  `ScenarioVersionPin` through your own evaluator and compares two scenarios order by order,
  as a Pareto report per order (`com.packvium.pareto.Pareto`) rather than one blended number.
- Recommendations: `com.packvium.recommendations.Recommendations.propose` builds a proposal
  from paired evidence only, and returns an empty `Optional` when the cohort is below the
  thresholds you set. `approve`, `approveCatalog` and `approvePolicy` are the only routes to
  a published change.
- Outcome ledger: `com.packvium.outcomes.OutcomeLedger` records what happened to each
  shipment, append-only, with idempotent duplicate delivery, corrections that supersede
  rather than overwrite, a closed field vocabulary per event type, and `viewAsOf` for what
  was known at a past instant.
- Holdout replay: `com.packvium.holdout.Holdout.evaluateOnHistory` scores a recommendation
  on decisions it was not derived from, split by time. It refuses a recommendation that
  cites a held-out decision, runs your independent validator on both arms before comparing
  anything, and reports realised damage, returns, repacks and overrides against the carton
  that actually shipped.
- `PackingConfig.balanced(long timeLimitMs)`.
- Two examples: `examples/Execution.java`, which ends with an operator pinning a box, and
  `examples/Intelligence.java`, which proves a carton change before it is published.

### Changed

- The examples pass a 60-second `time_limit_ms` as a safety fuse, so a busy machine cannot
  change their answers.
- The README is now a user guide, with a section and a short snippet for every
  capability. The test suite compiles every snippet, so the guide cannot drift from the API.
- Candidate selection keeps only the best few candidates while it searches, top-load checks
  reuse the stacking order of the placements already made, and the grid solver checks
  whether items are alike once per distinct item rather than once per unit. Results are
  unchanged.

## [1.1.0]

Answers every request the way Packvium 1.1.0 does in the other languages, checked byte for
byte against the 1.1.0 conformance corpus.

### Added

- Container doors: `container.access_directions` (and `Container.Builder.accessDirections`)
  names the walls a container is unloaded through, out of `+x -x +y -y +z -z`. With delivery
  stops, an item due earlier is never walled away from every door by one due later. Without
  the field nothing changes.

### Changed

- `container.pallet_overhang_limit` is reserved and refused with `unsupported_feature` until
  an engine implements it.
- Candidate search skips rules that cannot apply to the item being placed, and the spatial
  index answers wall and floor projections from its grid. Results are unchanged.

## [1.0.0]

First release of Packvium for Java. It is a full Java reimplementation that answers every
request the way Packvium 1.0.0 does in the other languages, checked byte for byte against
the 1.0.0 conformance corpus: 396 packing fixtures, the refusal cases and 55 commerce cases.

### Added

- `Packer` with the fast, balanced, quality and exact-small profiles, all six objectives,
  multi-container and nested packing, and an independent validator for every result.
- Exact lengths and weights (`Length`, `Weight`, `Dimensions.mm`, `Dimensions.inches`) with
  no floating point in geometry.
- The JSON request and result contract (`RequestPacker`, `ResultEncoder`) and a command-line
  entry point: `java -jar packvium-1.0.0.jar < request.json`.
- `com.packvium.commerce.Api`: quotes, eligibility policy and catalog versions.
- Eight runnable examples under `examples/`, each checked by the test suite.

Requires Java 17 or newer. No runtime dependencies.
