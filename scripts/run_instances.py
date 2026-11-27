#!/usr/bin/env python3
"""Deploy the loan process, run the three demo scenarios, and capture evidence."""

import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API = "http://localhost:8080/v2"
RESULTS = ROOT / "results"
TIMEOUT = 90

AUTH = base64.b64encode(b"demo:demo").decode()


def request(method, path, body=None, raw=False):
    data = None
    headers = {"Authorization": f"Basic {AUTH}", "Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            payload = response.read()
            if raw:
                return response.status, payload
            if not payload:
                return response.status, None
            return response.status, json.loads(payload)
    except urllib.error.HTTPError as e:
        payload = e.read().decode(errors="replace")
        raise RuntimeError(f"{method} {path} failed: HTTP {e.code}: {payload}")


def multipart_deploy(bpmn_path):
    boundary = "----camunda-loan-connector-boundary"
    content = bpmn_path.read_bytes()
    parts = []
    parts.append(f"--{boundary}\r\n".encode())
    parts.append(
        f'Content-Disposition: form-data; name="resources"; filename="{bpmn_path.name}"\r\n'
        "Content-Type: application/xml\r\n\r\n".encode()
    )
    parts.append(content)
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    data = b"".join(parts)
    req = urllib.request.Request(
        API + "/deployments",
        data=data,
        headers={
            "Authorization": f"Basic {AUTH}",
            "Accept": "application/json",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"deployment failed: HTTP {e.code}: {e.read().decode(errors='replace')}")


def wait_for(description, predicate, timeout=TIMEOUT, interval=1.0):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except Exception as exc:  # eventual-consistency endpoints lag behind the engine
            last = str(exc)
        time.sleep(interval)
    raise TimeoutError(f"timed out waiting for {description}; last={last}")


def search(path, filter_body):
    _, result = request("POST", path + "/search", {"filter": filter_body, "page": {"limit": 100}})
    return result.get("items", [])


def get_instance(key):
    _, result = request("GET", f"/process-instances/{key}")
    return result


def wait_state(key, wanted):
    return wait_for(
        f"process instance {key} to reach {wanted}",
        lambda: get_instance(key) if get_instance(key).get("state") == wanted else None,
    )


def start_instance(variables):
    body = {"processDefinitionId": "loan-approval", "variables": variables}
    _, result = request("POST", "/process-instances", body)
    return str(result["processInstanceKey"])


def collect_instance(key):
    instance = get_instance(key)
    variables = search("/variables", {"processInstanceKey": str(key)})
    incidents = search("/incidents", {"processInstanceKey": str(key)})
    try:
        elements = search("/element-instances", {"processInstanceKey": str(key)})
    except Exception as exc:
        elements = [{"collectionError": str(exc)}]
    return {
        "processInstanceKey": str(key),
        "state": instance.get("state"),
        "hasIncident": instance.get("hasIncident"),
        "variables": variables,
        "incidents": incidents,
        "elementInstances": elements,
    }


def docker_log(service, lines=500):
    proc = subprocess.run(
        ["docker", "compose", "logs", "--no-color", "--tail", str(lines), service],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    return proc.stdout


def main():
    RESULTS.mkdir(exist_ok=True)
    wait_for(
        "Orchestration API",
        lambda: request("GET", "/topology")[0] == 200,
        timeout=120,
        interval=2,
    )

    deployment = multipart_deploy(ROOT / "bpmn" / "loan-approval.bpmn")
    print("Deployed:", json.dumps(deployment, indent=2))

    scenarios = {}

    auto_key = start_instance({"applicantId": "AUTO-1001", "amount": 15000, "scenario": "auto-approve"})
    wait_state(auto_key, "COMPLETED")
    scenarios["auto-approve"] = collect_instance(auto_key)
    print(f"auto-approve completed: {auto_key}")

    manual_key = start_instance({"applicantId": "MANUAL-2001", "amount": 85000, "scenario": "manual-review"})
    task = wait_for(
        "manual review user task",
        lambda: (search("/user-tasks", {"processInstanceKey": str(manual_key), "state": "CREATED"}) or [None])[0],
    )
    user_task_key = str(task["userTaskKey"])
    status, _ = request("POST", f"/user-tasks/{user_task_key}/completion", {"variables": {"approved": True}})
    if status != 204:
        raise RuntimeError(f"manual task completion returned {status}")
    wait_state(manual_key, "COMPLETED")
    scenarios["manual-review"] = collect_instance(manual_key)
    scenarios["manual-review"]["completedUserTaskKey"] = user_task_key
    print(f"manual-review completed: {manual_key}, user task {user_task_key}")

    error_key = start_instance({"applicantId": "ERR-3001", "amount": 12000, "scenario": "legacy-error"})
    wait_state(error_key, "COMPLETED")
    scenarios["legacy-error"] = collect_instance(error_key)
    print(f"legacy-error completed through boundary error path: {error_key}")

    connector_log = docker_log("connectors", 800)
    mock_log = docker_log("mock-legacy", 800)
    (RESULTS / "connectors.log").write_text(connector_log)
    (RESULTS / "mock-legacy.log").write_text(mock_log)

    output = {
        "deployment": deployment,
        "scenarios": scenarios,
        "evidenceFiles": ["results/connectors.log", "results/mock-legacy.log"],
    }
    (RESULTS / "latest-run.json").write_text(json.dumps(output, indent=2))

    lines = [
        "# Latest loan approval run",
        "",
        "Recorded against the local Docker Compose stack. Full machine-readable evidence is in `latest-run.json`.",
        "",
    ]
    for name, item in scenarios.items():
        values = {}
        for variable in item["variables"]:
            variable_name = variable.get("name")
            if variable_name and variable_name not in values:
                raw = variable.get("value")
                try:
                    values[variable_name] = json.loads(raw) if isinstance(raw, str) else raw
                except Exception:
                    values[variable_name] = raw
        completed = [e.get("elementId") for e in item.get("elementInstances", []) if e.get("state") == "COMPLETED"]
        path = "` → `".join(completed)
        lines.extend([
            f"## {name}",
            "",
            f"- processInstanceKey: `{item['processInstanceKey']}`",
            f"- state: `{item['state']}`; hasIncident: `{item['hasIncident']}`",
            f"- completed path: `{path}`",
        ])
        credit_check = values.get("creditCheck")
        if isinstance(credit_check, dict):
            lines.append(
                f"- credit score: `{credit_check.get('creditScore')}` (`{credit_check.get('riskBand')}`), "
                f"connector latency `{credit_check.get('latencyMillis')} ms`"
            )
        for key in ["approvalNotification", "rejectionNotification", "legacyErrorNotification"]:
            notification = values.get(key)
            if isinstance(notification, dict):
                lines.append(f"- `{key}`: status `{notification.get('status')}`, id `{notification.get('notificationId')}`")
        if name == "manual-review":
            lines.append(f"- completed user task: `{item.get('completedUserTaskKey')}` with `approved=true`")
        if name == "legacy-error":
            lines.append("- legacy behavior: three forced HTTP 500 credit-check attempts, then BPMN boundary error `LEGACY_UNAVAILABLE`")
        lines.append("")
    (RESULTS / "latest-run.md").write_text("\n".join(lines))
    print(f"Evidence written to {RESULTS / 'latest-run.json'}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
