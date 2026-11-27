# Camunda 8 loan approval connector

A self-contained Camunda 8 loan approval demo with a custom Java outbound connector and a deliberately unreliable mock legacy core-banking API.

- Camunda 8.10 lightweight Docker Compose stack (Orchestration Cluster + Connectors)
- BPMN loan approval process with automatic approval, manual underwriting, notification, and a legacy-error boundary path
- Custom Java outbound connector built with the Camunda Connector SDK
- Spring Boot mock legacy system with slow responses, intermittent HTTP 500s, and forced error-path applicants
- Repeatable deployment and three-instance evidence runner

## Architecture

```text
┌──────────────┐        gRPC/REST         ┌────────────────────┐
│ Orchestration│◄────────────────────────►│ Connectors runtime │
│ Cluster      │  com.acme:legacy-core-   │                    │
│ (Zeebe +     │  banking:1 jobs          │ Legacy Core        │
│ Operate +    │                          │ Banking connector  │
│ Tasklist)    │                          └─────────┬──────────┘
└──────▲───────┘                                    │ HTTP/JSON
       │                                            ▼
       │                                   ┌────────────────────┐
       └───────────────────────────────────┤ mock-legacy        │
             Operate/Tasklist/evidence     │ Spring Boot API    │
                                           └────────────────────┘
```

Process flow:

1. **Application received** starts the instance with `applicantId` and `amount`.
2. **Credit check (legacy core)** invokes the custom connector operation `creditCheck`.
3. Amounts up to 25,000 with a credit score of at least 650 are **auto-approved**.
4. Other applications wait in a **manual underwriting review** user task assigned to `demo`.
5. The reviewer decision routes to approved or rejected notification.
6. Notification tasks invoke the same custom connector operation `notifyApplicant`.
7. Persistent legacy timeouts/5xx responses exhaust retries, are mapped to BPMN error `LEGACY_UNAVAILABLE`, and take the boundary path to a manual-follow-up notification.

## Repository layout

```text
bpmn/loan-approval.bpmn                    BPMN process
connector/                                 Java connector Maven project
connector/target/…jar                      Built connector artifact after build
element-templates/                         Copy of generated connector element template
mock-legacy/                               Spring Boot mock legacy core banking service
scripts/build-all.sh                       Build connector + mock jars
scripts/run_instances.py                   Deploy BPMN, run scenarios, capture evidence
results/latest-run.json                    Full machine-readable evidence from the recorded run
results/latest-run.md                      Human-readable recorded-run summary
results/connectors.log                     Connector runtime log captured after the run
results/mock-legacy.log                    Mock legacy log captured after the run
docker-compose.yaml                        Camunda 8.10 lightweight stack, patched for this demo
```

## Prerequisites

- Docker with Docker Compose
- Java 21 and Maven, **or** Docker for the Maven build containers used by the build scripts
- Python 3.10+ for the scenario runner

## Run it

```bash
# 1. Create local environment values. The committed file contains placeholders;
#    local-only values are fine for this demo, but do not commit .env.
cp .env.example .env

# 2. Build both Java artifacts. The scripts use local Maven when JAVA_HOME is set,
#    otherwise they run Maven in a container.
./scripts/build-all.sh

# 3. Start Camunda, the connector runtime, and the mock legacy API.
docker compose up -d --build

# 4. Wait until all three services are healthy.
docker compose ps

# 5. Deploy the process and run the three scenarios.
python3 scripts/run_instances.py
```

Useful local endpoints:

| Component | URL | Credentials |
|---|---|---|
| Operate | <http://localhost:8080/operate> | `demo` / `demo` |
| Tasklist | <http://localhost:8080/tasklist> | `demo` / `demo` |
| Orchestration API | <http://localhost:8080/v2/topology> | unprotected locally; scripts send `demo:demo` |
| Connector health | <http://localhost:8086/actuator/health/readiness> | none |
| Mock legacy health | <http://localhost:8090/actuator/health> | none |

Stop and clean up:

```bash
docker compose down          # keep Camunda data volumes
docker compose down -v       # full reset
```

## Mock legacy behavior

`POST /api/credit-checks`:

- normal applicants: approximately 450 ms latency and a deterministic score
- applicant IDs beginning with `SLOW`: 2.6 s latency, enough to trigger the connector's 1.8 s timeout in the BPMN input
- applicant IDs beginning with `ERR`: always HTTP 500, used by the recorded legacy-error scenario
- every fifth non-forced credit-check call: HTTP 500, simulating intermittent legacy instability

`POST /api/notifications` returns a queued notification after about 150 ms and logs the notification payload.

## Connector behavior and failure policy

The connector is `com.acme.loan.connector.LegacyCoreBankingConnector`, exposed through the Connector SDK SPI as type `com.acme:legacy-core-banking:1` with two operations:

- `creditCheck`
- `notifyApplicant`

Failure classification:

| Upstream condition | Connector error | Retryable? |
|---|---|---|
| HTTP 5xx | `LEGACY_UNAVAILABLE` | yes |
| Request timeout | `LEGACY_TIMEOUT` | yes |
| Network/IO failure or interruption | `LEGACY_UNAVAILABLE` | yes |
| HTTP 4xx | `LEGACY_CLIENT_ERROR` | no |
| Invalid/malformed success payload | `LEGACY_BAD_RESPONSE` | no |
| Invalid input | Connector input error | no |

Retryable failures throw `ConnectorRetryException`. The BPMN task sets `retries="3"` and `retryBackoff="PT2S"`. The error expression intentionally waits until the final job attempt (`job.retries <= 1`) before mapping the technical connector error to BPMN error `LEGACY_UNAVAILABLE`; earlier attempts return `null` so Zeebe can retry.

## Recorded results

The recorded run deployed `loan-approval` version 1 and completed all three instances. Full evidence is in [`results/latest-run.json`](results/latest-run.json), with raw service logs in [`results/connectors.log`](results/connectors.log) and [`results/mock-legacy.log`](results/mock-legacy.log).

| Scenario | Input | Result | Evidence |
|---|---|---|---|
| Auto-approve | `AUTO-1001`, amount `15000` | completed through `NotifyApprovedTask` and `EndApproved` | score `762`, approval notification queued |
| Manual review | `MANUAL-2001`, amount `85000` | waited in `ManualReviewTask`, completed after API completion with `approved=true`, then notification sent | user task `2251799813685474`, score `714`, approval notification queued |
| Legacy error | `ERR-3001`, amount `12000` | three HTTP 500 attempts, boundary error, manual-follow-up notification, completed | mock log calls 3–5; `LEGACY_UNAVAILABLE`; no incident |

The legacy-error retry evidence is visible in `results/mock-legacy.log`: credit-check calls 3, 4, and 5 are the same forced-failure applicant retried at two-second intervals before `NotifyLegacyErrorTask` runs.

Operate screenshots from the same run are committed at `results/operate-completed-instances.png` (all three completed instances) and `results/operate-legacy-error-instance.png` (the boundary-error path and variables).

## Modeling the connector yourself

The generated element template is at `element-templates/legacy-core-banking-connector.json`. It is regenerated by the Maven build from connector annotations; do not hand-edit the copy in `connector/element-templates/`.

The included BPMN is already wired manually with the equivalent task headers and input mappings, so Web Modeler/Desktop Modeler template installation is not required for the demo. To edit interactively, add the generated element template to your Modeler and open `bpmn/loan-approval.bpmn`.

## Deployment snags checklist

Everything below actually happened during setup; the fix is what is now in the repository.

| Snag | Symptom | Fix |
|---|---|---|
| Docker Hub rate limiting | `docker compose pull` failed for `camunda/connectors-bundle:8.10.9` with `429 Too Many Requests` | Switched Camunda image references to the official Camunda registry documented for mirroring: `registry.camunda.cloud/camunda/...` |
| Incorrect custom connector mount path | Mounting the connector jar into `/opt/app` made the runtime discover the SPI file but fail startup repeatedly with `java.lang.ClassNotFoundException: io.camunda.connector.api.outbound.OutboundConnectorProvider` | Inspected the image's `/start.sh`; it configures `-Dloader.path=/opt/custom/`. Mounted the jar at `/opt/custom/legacy-core-banking-connector.jar` |
| Numeric element-template defaults | Maven failed during template generation: `Value of a Number property must be a Number` | Added `defaultValueType = DefaultValueType.Number` to numeric `@TemplateProperty` defaults |
| Missing local Java/Maven toolchain | The VM had Java 17 and no `mvn`; the connector template requires Java 21 | Downloaded a temporary local JDK 21/Maven for validation and made the repository build scripts fall back to Maven Docker images for normal use |
Two preventive adjustments were not breakages: the build scripts compile jars before Compose so generic Maven/JRE Docker Hub images are not needed at runtime, and the mock healthcheck uses Bash `/dev/tcp` rather than `curl` because the Camunda runtime image contains `bash` and `wget` but not `curl`.

## Notes and limitations

- This is a local-development demo, not production hardening. The Compose stack is the lightweight H2-backed distribution.
- The connector jar is small because it only contains project classes; connector SDK, Jackson, validation, and logging classes are supplied by the connector runtime classloader. If you add third-party libraries, shade them deliberately and relocate dependencies that the runtime also bundles.
- Intermittent failures and slow responses are intentionally built into the mock. Do not point this connector configuration at a real core banking system without replacing test credentials, tightening timeouts, and reviewing retry/idempotency semantics.
