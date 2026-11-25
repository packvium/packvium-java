# Security policy

## Supported versions

Only the latest `1.x` release receives fixes. There is no long-term support branch.

## Reporting a vulnerability

Please report privately, not in a public issue. Use GitHub's **Report a vulnerability**
button under the Security tab, which opens a private advisory.

Include the version, the Java version, a minimal request that reproduces the problem, and
what you observed. A crash, a hang or a wildly wrong result on a small input is worth
reporting even if you are unsure it is a security matter.

You can expect an acknowledgement within a few working days and an assessment after that.
Please give us a chance to ship a fix before disclosing publicly.

## Threat model

This is a computation library. It has **no runtime dependencies**, opens no network
connections, spawns no processes, and executes no user-supplied code except the extension
objects you register yourself. The command-line entry point (`java -jar`) reads one request
from standard input and writes the result to standard output; it touches no other file.

The realistic risks are therefore about untrusted **input**:

- **Resource exhaustion.** A request with a large item count, many container types or a
  permissive solver profile can consume substantial CPU and memory. If you accept requests
  from untrusted callers, set `time_limit_ms` and an `effort_budget`, cap item quantities and
  container inventory, and run the call where you can bound memory. The deadline is honoured
  by the search, but a single enormous request can still allocate a lot before the first
  check.
- **Integer magnitude.** Dimensions and weights become exact 64-bit integer ticks (16 000 per
  millimetre). A value too large for 64 bits is refused rather than allowed to overflow, but
  large values within range still cost time and memory. Validate dimensions against a sane
  maximum before passing them in.
- **Malformed input.** Bad units, unparseable numbers and contradictory constraints throw an
  exception rather than producing a wrong packing. Do not suppress those exceptions.

A result that reports success has passed independent validation, but validation checks the
constraints you declared. It cannot know about a constraint you did not express.

## Extensions

Custom constraints, orderings, scorers, container selectors and solvers run with the
privileges of your process. Treat a third-party extension as you would any other dependency:
read it before you register it.

## How releases are published

Every release is built and published from this repository's CI, never from a maintainer's
workstation.

- **Maven Central.** The release workflow signs every artifact with GPG and uploads it to
  Maven Central. The signing key and the Central token are stored as CI secrets of this
  repository.
- **Provenance.** Each release also carries a GitHub build-provenance attestation, which binds
  the published files to the workflow run, commit and tag that produced them.
- **Immutable versions.** A version on Maven Central cannot be changed or removed. A defective
  or compromised version is therefore superseded by a fixed release under a new version, and
  the security advisory names the affected versions.
- **Recovery.** If the signing key or the Central token is suspected compromised, it is
  revoked and replaced at once, the workflow's recent runs are audited, and the fix is
  released from a clean, reviewed commit under a new version.
