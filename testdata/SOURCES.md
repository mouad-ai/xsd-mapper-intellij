# Test corpus sources

Third-party schemas in `corpus/` are unmodified copies of the official releases. The official hosts
(docs.oasis-open.org, unece.org, iso20022.org) were not reachable from the environment that assembled the corpus, so
each set was fetched from a mirror; the mirror and the official location are both recorded below.
`corpus/checksums.sha256` holds the SHA-256 of every downloaded file (`cd corpus && sha256sum -c checksums.sha256`).

| Set | Files | Official source | Fetched from | License |
|---|---|---|---|---|
| OASIS UBL 2.1 (OS, 4 Nov 2013): Invoice, CreditNote and the `common/` modules they import | `corpus/ubl-2.1/xsd/` (16 files) | https://docs.oasis-open.org/ubl/os-UBL-2.1/ (`xsd/maindoc`, `xsd/common`) | https://github.com/highsource/jsonix-support/tree/master/u/ubl/schema/2.1/xsd (file headers read "UBL 2.1 OS, Release Date 04 November 2013") | © OASIS Open 2013, distributed under the [OASIS IPR Policy](https://www.oasis-open.org/policies-guidelines/ipr/): may be copied and distributed, including in derivative works, with the copyright notice kept. The notice is in each file. |
| UN/CEFACT Cross Industry Invoice D16B (100pD16B) with its code lists | `corpus/cii-d16b/` (54 files, directory layout as published) | https://unece.org/trade/uncefact/xml-schemas (D16B) | `external/schemas/d16b/` inside the Maven Central artifact [`com.helger.cii:ph-cii-d16b:10.1.0`](https://repo1.maven.org/maven2/com/helger/cii/ph-cii-d16b/10.1.0/) | © UN/CEFACT 2016. Copy and distribution allowed without restriction provided the notice is kept; the documents themselves may not be modified. The notice is in each file. |
| ISO 20022 pain.001.001.09 (CustomerCreditTransferInitiationV09) | `corpus/iso20022/pain.001.001.09.xsd` | https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive (Payments Initiation) | https://github.com/fortesp/xsd2xml/blob/master/tests/resources/pain.001.001.09.xsd; identical to the copy in the PyPI package [`pain001` 0.0.72](https://pypi.org/project/pain001/0.0.72/) | Published free of charge by the ISO 20022 Registration Authority; reuse is governed by the terms of use on iso20022.org (not re-checked here because the site was unreachable). |
| ISO 20022 camt.053.001.08 (BankToCustomerStatementV08) | `corpus/iso20022/camt.053.001.08.xsd` | https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive (Cash Management) | https://github.com/genkgo/camt/blob/master/assets/camt.053.001.08.xsd | As above. |

Everything under `ugly/` was written by hand for this project (same license as the repository). Each directory has a
`main.xsd` entry point and a comment at its top saying what it exercises:

| Directory | What it exercises |
|---|---|
| `ugly/include-chain` | Five-level `xs:include` chain across directories, a chameleon include, `xs:redefine` |
| `ugly/cross-namespace-imports` | Imports across four namespaces, one namespace split over two documents imported from different schemas, a no-namespace import, qualified vs unqualified local elements, a qualified global attribute |
| `ugly/substitution-groups` | Abstract and concrete heads, transitive, abstract and cross-namespace members, circular imports |
| `ugly/anonymous-types` | Nested anonymous complex types; anonymous restriction, list and union simple types |
| `ugly/recursion` | Direct, indirect, anonymous-type (via `ref`) and model-group recursion |
| `ugly/choice-in-sequence` | Choices in sequences, nested choices, sequences in choices, repeating choices, group refs, `xs:all` |
| `ugly/wildcards` | `xs:any` with `##any`, `##other` and namespace lists, each `processContents`, `xs:anyAttribute`, untyped elements |
| `ugly/mixed-content` | Mixed content with repeating inline markup, text-only mixed content |
| `ugly/facets` | Every facet the model keeps, stacked patterns, built-in type facets, `nillable`/`default`/`fixed` |

`entrypoints.txt` lists the entry schemas the tests load.
