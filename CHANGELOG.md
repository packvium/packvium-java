# Changelog

What changed in `com.packvium:packvium` on Maven Central, release by release. The format
follows [Keep a Changelog](https://keepachangelog.com/1.1.0/) and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
