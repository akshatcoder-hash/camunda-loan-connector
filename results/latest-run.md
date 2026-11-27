# Latest loan approval run

Recorded against Camunda 8.10.12 with connectors bundle 8.10.9. Full machine-readable evidence is in `latest-run.json`.

## auto-approve

- processInstanceKey: `2251799813685423`
- state: `COMPLETED`; hasIncident: `False`
- completed path: `StartApplicationReceived` → `CreditCheckTask` → `GatewayAutoDecision` → `NotifyApprovedTask` → `EndApproved`
- credit score: `762` (`LOW`), connector latency `618 ms`
- `approvalNotification`: status `QUEUED`, id `23bdbee5-a6ec-446d-961b-55e7d299cb5e`

## manual-review

- processInstanceKey: `2251799813685454`
- state: `COMPLETED`; hasIncident: `False`
- completed path: `StartApplicationReceived` → `CreditCheckTask` → `GatewayAutoDecision` → `ManualReviewTask` → `GatewayReviewDecision` → `NotifyApprovedTask` → `EndApproved`
- credit score: `714` (`LOW`), connector latency `456 ms`
- `approvalNotification`: status `QUEUED`, id `3597eb2a-5390-423f-8a36-afd67e04e713`
- completed user task: `2251799813685474` with `approved=true`

## legacy-error

- processInstanceKey: `2251799813685493`
- state: `COMPLETED`; hasIncident: `False`
- completed path: `StartApplicationReceived` → `BoundaryLegacyError` → `NotifyLegacyErrorTask` → `EndLegacyError`
- `legacyErrorNotification`: status `QUEUED`, id `26602b5d-041d-4e82-98b2-bb519d3c786a`
- legacy behavior: three forced HTTP 500 credit-check attempts, then BPMN boundary error `LEGACY_UNAVAILABLE`
