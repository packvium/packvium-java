# Changelog

What changed in `com.packvium:packvium` on Maven Central, release by release. The format
follows [Keep a Changelog](https://keepachangelog.com/1.1.0/) and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.4.0]

Answers every request the way Packvium 1.4.0 does in the other languages, checked byte for
byte against the 1.4.0 conformance corpus.

### Added

- Fixed placements: a request's `fixed_placements` (and `Packer.pack(items, containers,
  fixedPlacements)`) names items already in a known place by container type, instance and
  origin. They take the first instances of their type, their containers open first and are
  kept, and the solve packs around them with their weight, support and top load counted.
  They come back with `"fixed": true`, and the independent validator refuses an answer that
  drops, moves or invents one. A set that is not the schema's shape or cannot hold on its own
  is refused before any search with `FixedPlacementException` (`invalid_fixed_placement`).
- Structured request errors: `InvalidRequestException`, an `IllegalArgumentException` with
  `errorCode()` `invalid_request`, a `reason()` from a closed set and the RFC 6901 `field()`
  of the bad value, and the message every engine prints, such as
  `invalid_request: /items/0/quantity: must be at least 1`.
- Plan revisions: `com.packvium.revisions.PlanRevision` builds `packvium-plan-revision/v1`
  documents (`root`, `derive`, `applyEvents`), audits a chain (`verifyChain`) and names a
  document by `digest`, the SHA-256 of its RFC 8785 bytes. The bytes are the same in every
  engine. `RevisionOutcomes` records a revision's events in an `OutcomeLedger`, with the new
  `item_missing` and `placement_verified` event types.
- An `examples/Revisions.java` example.

### Changed

- Every schema minimum is enforced: a quantity or limit below its floor, a negative length or
  weight, `max_containers` below 1 and an effort limit below 1 are refused instead of
  answered. `PackingConfig`, `EffortBudget` and `Container` refuse the same values when built
  in code.
- An integral float, such as `100.0` for a length or `2.0` for a quantity, is read as the
  integer it is, as JavaScript cannot send anything else.
- Containers are numbered per type from 1 on the container-plan beam (`quality`), as on every
  other path and in every engine.
- A refusal that quotes an out-of-range float spells it as the reference does (`1e+300`).
- A request the model cannot be built from is refused with the reference's own text, the one
  spelling every engine prints: domain, unit and configuration messages are lower-case as in
  Python (`item id is required`, `all dimensions must be positive`), an obstacle without an
  `id` or a rate table without one of its lists names the missing key (`'id'`), a list or
  object in a tag set is `unhashable type`, and a `null` obstacle coordinate or length unit is
  refused instead of read as the default.
- A negative length or weight is refused as negative however large it is, instead of as an
  unreadable measure once past the 64-bit tick range.
- An artifact whose position tick is past 64 bits is refused as `number_out_of_range`, as in
  the other engines, instead of `invalid_plan_input`.
- The beam search sorts the remaining items' volumes and weights once per batch instead of
  once per node. Results are unchanged.

## [1.3.0]

Answers every request the way Packvium 1.3.0 does in the other languages, checked byte for
byte against the 1.3.0 conformance corpus.

### Added

- Operational artifacts: `com.packvium.artifacts.OperationalArtifact.build(request, result,
  loadingOrders)` wraps the execution plan unchanged in one document a warehouse or
  transport system can act on without an engine: the request itself, the catalog versions
  and the solver's deterministic settings, tick geometry for every placement, and a work
  order that copies the result's own display values. `provenance.replay` says `exact` only
  when the search finished inside its time limit. `fromJson` and `parse` read JSON text.
- Exports: `ArtifactExports.json` (the canonical form), `csv` (RFC 4180, a row per step and
  per unplaced item) and `workOrderHtml` (one self-contained page to print, no script or
  external resource). The bytes are the same in every engine. A document the builder or an
  exporter cannot carry the same way everywhere throws `OperationalArtifactException`, whose
  `errorCode()` comes from a closed set.
- `com.packvium.support.CanonicalJson`, the RFC 8785 writer behind both, and an
  `examples/Artifacts.java` example.

### Changed

- `Plan.canonicalJson` is RFC 8785, shared with the artifact. Every plan the 1.2.0 corpus
  produces keeps its bytes; a plan holding a float or a key outside the Basic Multilingual
  Plane now matches the other engines, and a number beyond 2^53 - 1 is refused.
- Floats are written with their shortest round-trip digits on every supported JDK. The
  1.2.0 spelling could write 17 digits where 16 suffice, for a few doubles just above a power
  of two.
- The execution plan carries a score as the result holds it, as the Python adapter does,
  instead of casting each entry to an integer. A fractional score is no longer truncated: the
  work order refuses it as `invalid_value`, and comparing it with an alternative's score is
  refused. A position tick is read as Python's `int()` reads it, so `true` is 1 and `" 7 "` is
  7. Every plan the corpus produces keeps its bytes.
- A pinned catalog version and a pinned tariff version are found by position instead of by
  scanning the history, and a two-axis Pareto report sorts once instead of comparing every
  pair of candidates. Results are unchanged.

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
