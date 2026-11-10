# Contributing

Thanks for looking. This library is small, dependency-free and deliberately strict about
exactness. Please keep it that way.

## Getting set up

```bash
mvn package
```

The suite must be green before you change anything, so that any later failure is yours.
It runs every example in `examples/` as well as the unit tests.

## The rules that matter here

**No floats in geometry or feasibility.** Every length is an integer number of ticks
(1/16000 mm) and every weight an integer number of 1/8 µg. If a change makes a placement
decision depend on binary floating point, it is wrong even if the tests pass.

**No runtime dependencies.** The jar runs with nothing else on the classpath. A new
dependency needs a very good argument.

**Inputs are immutable.** A pack call must never change the items or containers it was
given. Requests, configuration and results are records or unmodifiable collections.

**Determinism.** The same input and seed must produce the same output. Never let the
iteration order of a `HashMap` or `HashSet` reach a result; sort explicitly.

**Every fixed defect gets a regression test** that fails before the fix and passes after.

## Behavioural changes

This package is one of several independent implementations of the same documented
contract, and they must produce identical results for the same request. So:

- A change to solver behaviour, the objective vector, serialization field names or status
  semantics is a **cross-language change**. Open an issue describing it before writing
  code, so the other implementations move with it.
- A change confined to Java internals (typing, refactoring, performance without a change
  in output) is local and needs no coordination.

## Pull requests

- One logical change per pull request.
- Commit messages in imperative mood, under 72 characters:
  `type(scope): description` with `feat`, `fix`, `refactor`, `chore`, `docs` or `test`.
- `mvn package` must pass on Java 17. The build treats every compiler warning as an error.
- Do not bump the version; releases are cut separately.

## How your feedback is handled

Every issue and discussion is read by a maintainer. This is what happens next:

1. **New issues are labelled `needs-triage`.** A maintainer answers with one of: a question
   back to you, a confirmation, or a reason it will not be taken on.
2. **A confirmed bug** gets the `bug` label and is reproduced from your request. A wrong result
   in one package is checked against the others, because all of them implement the same
   contract and must agree.
3. **An accepted feature request or idea** is added to the project's backlog, and the issue is
   labelled `accepted` with a comment saying so. An idea from Discussions is first turned into
   an issue and linked back to the discussion.
4. **When the change ships**, the issue is closed with the release it shipped in. A change to
   the request or result format can only ship in a release that is allowed to change it, so
   it may take more than one release.
5. **Declined** requests are closed with the reason, so you are never left waiting on a
   silent issue.

Questions go to **Discussions → Q&A**; security problems go to **Report a vulnerability**
under the Security tab, never to a public issue.
