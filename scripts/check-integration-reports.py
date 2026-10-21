"""Require every integration suite and allow only known environment skips.

Set QUACK_IT_EXPECTED_VERSION and QUACK_IT_EXPECTED_QUACK to pin an optional
fixture to an exact released server and signed core extension.
"""

import os
import pathlib
import re
import xml.etree.ElementTree as ET


expected = set()
for source in pathlib.Path("src/test").rglob("*.java"):
    text = source.read_text()
    if not (re.search(r'@Tag\(\s*"integration"\s*\)', text)
            or source.stem.endswith("IntegrationTest")):
        continue
    package = re.search(r"^package\s+([\w.]+)\s*;", text, re.MULTILINE)
    if package:
        expected.add(package.group(1) + "." + source.stem)
if not expected:
    raise SystemExit("No integration test classes discovered")

allowed_skips = {
    ("com.gizmodata.quack.jdbc.it.QuackV3PreviewTest",
     "queryFetchAndSendDataAgainstPreview"): "Set QUACK_V3_IT_DUCKDB and QUACK_V3_IT_EXTENSION",
    ("com.gizmodata.quack.jdbc.transport.QuackHttpTransportTest",
     "fallsThroughToIpv6WhenIpv4HasNoListener"): "ipv6LoopbackAvailable",
    ("com.gizmodata.quack.jdbc.transport.QuackHttpTransportLimitsTest",
     "connectFailuresStillTryNextAddress"): "requires multiple localhost addresses",
}
seen = set()
errors = []
reports = sorted(pathlib.Path("target/surefire-reports").glob("TEST-*.xml"))
for report in reports:
    root = ET.parse(report).getroot()
    for suite in root.iter("testsuite"):
        cases = suite.findall("testcase")
        name = suite.get("name", "")
        if name in expected:
            if not cases or int(suite.get("tests", "0")) == 0:
                errors.append(f"Empty integration report: {report}")
            else:
                seen.add(name)
        for case in cases:
            classname = case.get("classname", name)
            method = case.get("name", "").removesuffix("()")
            if classname in expected:
                seen.add(classname)
            if case.find("failure") is not None or case.find("error") is not None:
                errors.append(f"Failed test: {classname}#{method}")
            skipped = case.find("skipped")
            if skipped is not None:
                reason = skipped.get("message", "") + " " + "".join(skipped.itertext())
                allowed_reason = allowed_skips.get((classname, method))
                if classname in expected or not allowed_reason or allowed_reason not in reason:
                    errors.append(f"Unexpected skip: {classname}#{method}: {reason}")
errors.extend(f"Missing integration report: {name}" for name in sorted(expected - seen))

version = os.getenv("QUACK_IT_EXPECTED_VERSION")
quack_revision = os.getenv("QUACK_IT_EXPECTED_QUACK")
if version or quack_revision:
    if not (version and quack_revision):
        errors.append("Both QUACK_IT_EXPECTED_VERSION and QUACK_IT_EXPECTED_QUACK are required")
    fixture_logs = sorted(pathlib.Path("target/quack-fixture-logs").glob("stock-*/server.log"))
    if not fixture_logs:
        errors.append("No signed-server fixture identity logs found")
    for log in fixture_logs:
        text = log.read_text()
        if version and f"runtime={version}," not in text:
            errors.append(f"Wrong DuckDB version in {log}")
        quack_identities = [line for line in text.splitlines()
                            if "JDBC identity=Identity[" in line and "name=quack," in line]
        if len(quack_identities) != 1:
            errors.append(f"Expected one Quack identity in {log}")
        elif quack_revision and f"version={quack_revision}," not in quack_identities[0]:
            errors.append(f"Wrong Quack extension revision in {log}")

print(f"Integration reports: {len(seen & expected)}/{len(expected)} classes; {len(reports)} total reports")
if errors:
    raise SystemExit("\n".join(errors))
