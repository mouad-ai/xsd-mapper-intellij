# CLAUDE.md — XSD Mapper for IntelliJ

## What this project is
A JetBrains IDE plugin for integration developers who work with XML Schemas (XSD).
Product name is TBD; the working name is "XSD Mapper". It will be a freemium plugin on JetBrains Marketplace.

- **Free tier:** XSD → sample XML generator that is better than IntelliJ's built-in one. It resolves `xs:include`/`xs:import` correctly, generates realistic values that respect facets, and produces one variant per `xs:choice` branch.
- **Paid tier:** visual source-XSD → target-XSD mapper that generates XSLT 3.0, runs it on a sample, validates the output against the target XSD, and reports mapping coverage.

Positioning: **"verify your mapping"**, not "draw lines". AI can write XSLT; this plugin proves it's correct.

## Architecture
Gradle multi-module, Kotlin, JDK 21.

```
/core       Pure Kotlin/JVM library. NO IntelliJ dependencies. Fully unit-tested.
  schema/     XSD loading (Xerces XSModel) → our own immutable SchemaTree model
  samplegen/  SchemaTree → sample XML documents
  xmap/       .xmap mapping model + JSON (de)serialization
  xsltgen/    .xmap + SchemaTrees → XSLT 3.0 text
  verify/     run XSLT (Saxon-HE), validate against XSD, coverage + impact reports
/plugin     IntelliJ plugin (IntelliJ Platform Gradle Plugin 2.x). Thin UI layer over /core.
/canvas     React + TypeScript + Vite app for the mapping canvas, rendered in JCEF.
            Built output is copied into /plugin resources at build time.
/testdata   Schema corpus + sample XML used by all tests.
/tools      Dev/research scripts (not shipped).
```

Rules:
- Business logic lives in `/core`. The plugin module only wires UI, actions, settings, and licensing.
- `SchemaTree` is our model. Nothing outside `core/schema` touches Xerces types directly.
- The `.xmap` file is the **source of truth**. XSLT is a generated artifact. There's no round-trip from hand-edited XSLT in v1.
- Long work (schema loading, transformation runs) runs on background threads with progress indicators and cancellation. Never block the EDT.
- JCEF ↔ Kotlin communication goes through a single typed message bridge (JSON messages), defined in one place on each side.

## Test corpus (/testdata)
Every change must keep the whole corpus green:
- OASIS UBL 2.1 (Invoice, CreditNote)
- UN/CEFACT CII D16B (CrossIndustryInvoice)
- ISO 20022 pain.001.001.09 and camt.053.001.08
- Hand-written "ugly" schemas: deep include chains, imports across namespaces, substitution groups, anonymous types, recursive types, `xs:choice` inside `xs:sequence`, `xs:any`, mixed content.

Record the source URL and license of each downloaded schema in `testdata/SOURCES.md`.

## Conventions
- Kotlin official style. No wildcard imports.
- Tests: JUnit 5 + AssertJ (or kotest assertions). Corpus-driven tests over hand-picked unit cases where possible.
- Generated samples must validate against their own schema. This is an invariant and is tested for every corpus schema.
- Generated XSLT must be readable: one template per mapped target element, with comments pointing back to the `.xmap` link id.
- Commit in small, working steps. Each commit builds and passes tests.

## Scope discipline
v1 functions in the mapper: copy, constant, concat, substring, format-date, lookup table, for-each over repeating groups. Nothing else.
Not in v1: multiple sources, conditions/if-else, XQuery, JSON mapping, two-way XSLT sync, AI features.
If a task seems to need something out of scope, stop and ask instead of building it.

## Commands
- `./gradlew build` — build everything + tests
- `./gradlew :core:test` — fast core tests
- `./gradlew :plugin:runIde` — sandbox IDE
- `./gradlew :plugin:verifyPlugin` — Marketplace compatibility check
