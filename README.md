# Packvium for Java

Deterministic 3D cartonization and container packing in Java, with exact integer geometry
and no runtime dependencies.

Packvium for Java is the Java port of Packvium. It accepts the same requests and produces
the same results as the Python, PHP, Rust and JavaScript engines. It is checked against
their shared conformance corpus, and each result matches the reference one byte for byte.

> **Version 1.0.0.** This release corresponds to Packvium 1.0.0 in the other languages.
> Its tests run the 1.0.0 conformance corpus: every packing fixture and every commerce
> case. Field names, status codes and the objective vector stay the same for all of 1.x.
> A later 1.x release can replace any earlier one without code changes.

```xml
<dependency>
    <groupId>com.packvium</groupId>
    <artifactId>packvium</artifactId>
    <version>1.0.0</version>
</dependency>
```

This artifact is not on Maven Central yet. Until it is published, build it and install it
into your local repository with `mvn install`.

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

Fractional inches are stored exactly, without rounding to millimetres:

```java
Dimensions.inches("12 3/8", "8 1/2", "3/4");
```

To get the result in the same JSON format the other engines produce, encode it with
`new ResultEncoder("mm", "g").encode(result)` and then `JsonWriter.write(...)`.

## Examples

The [`examples/`](examples) directory has eight runnable files. You can read each one from
top to bottom and run it with the JDK source launcher, with no project set up around it.
The test suite (`ExamplesTest`) runs all eight and compares the output of each to what its
PHP counterpart prints.

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

```bash
mvn -q package -DskipTests
java -cp target/packvium-1.0.0.jar examples/Objectives.java
```

## Command line

The jar can also be run as a command-line program. It reads a JSON packing request on
standard input and writes the result JSON to standard output. The request and result
formats are the ones every other Packvium engine uses.

```bash
echo '{"items":[{"id":"box","quantity":8,"dimensions":{"length":"50","width":"50","height":"50"}}],
       "containers":[{"id":"carton","inner_dimensions":{"length":"100","width":"100","height":"100"}}]}' \
  | java -jar target/packvium-1.0.0.jar
```

When a request is refused, the program exits with status 1 and prints the reason on
standard error, with the same wording the other engines use. When standard input is
empty, it exits with status 2.

## What it does

- **Exact arithmetic.** Length is counted in ticks of 1/16000 mm and weight in ticks of
  1/8 µg. Coordinates are never floating-point numbers, so rounding cannot change where an
  item is placed.
- **Real constraints.** Weight and payload limits, allowed rotations, keep-upright,
  floor-only, non-stackable, top-load limits, minimum support ratio, incompatible tags,
  clearance and obstacles.
- **Several solvers, not one.** Grid, layer, extreme-point, maximal-space, homogeneous-block
  and exact search for small problems. Which ones run depends on the shape of the problem
  and the chosen profile.
- **Checked answers.** An independent validator checks every solution again. Every item
  that was not placed is listed with a reason code.
- **Deterministic.** The same input and seed always give the same result. The only effect
  of the clock is the time limit you set.
- **Multiple and nested containers.** Items can be split across several containers, and
  containers can be packed inside other containers.
- **Commerce.** `com.packvium.commerce.Api` prices a shipment, evaluates an eligibility
  policy and resolves catalog versions. These are deterministic functions of the document
  you pass in. They do not read the clock, use the network or keep hidden state.

## Differences from the other engines

Valid requests get identical results in every engine. The Java port differs in a few
places, and none of them shows up in the conformance corpus:

- Lengths and weights are stored as 64-bit ticks, as in PHP. If a value would overflow,
  the request is refused, whereas Python would keep calculating with larger integers.
- The parallel portfolio cannot be enabled from a request, so it is not included. Every
  start runs one after another, which is how the reference engine runs them.

The full conformance corpus runs in the Packvium suite repository, where all the engines
are checked against the same fixtures. This repository runs the unit tests and the
examples.

## Requirements

Java 17 or newer. There are no runtime dependencies.

## The Packvium family

There are packages for Python (`pip install packvium`), PHP
(`composer require packvium/packvium`), Rust, Node.js and the browser. They all accept
the same requests and return the same results. Documentation, the constraint reference
and benchmarks are at [packvium.com](https://packvium.com).

## License

MIT.
