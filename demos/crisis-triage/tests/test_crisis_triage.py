# ABOUTME: Static validation tests for the crisis triage demo.
# ABOUTME: Tests file structure, XML format, code structure, and template consistency.

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


DEMO_DIR = Path(__file__).resolve().parents[1]
MODEL_PATH = DEMO_DIR / "crisis-triage.nlogox"
TRIAGE_TEMPLATE_PATH = DEMO_DIR / "triage-template.yaml"
DISPATCHER_TEMPLATE_PATH = DEMO_DIR / "dispatcher-template.yaml"
CONFIG_PATH = DEMO_DIR / "config.txt"
README_PATH = DEMO_DIR / "README.md"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def parse_model() -> ET.Element:
    return ET.parse(MODEL_PATH).getroot()


def model_code_only() -> str:
    root = parse_model()
    code_elem = root.find("code")
    if code_elem is None or code_elem.text is None:
        raise AssertionError("unable to extract <code> content from model XML")
    return code_elem.text


def parse_config(path: Path) -> dict[str, str]:
    data: dict[str, str] = {}
    for raw in read(path).splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        data[key.strip()] = value.strip()
    return data


class TestCrisisTriageArtifacts(unittest.TestCase):
    def test_required_files_exist(self) -> None:
        required = [
            MODEL_PATH,
            TRIAGE_TEMPLATE_PATH,
            DISPATCHER_TEMPLATE_PATH,
            CONFIG_PATH,
            README_PATH,
        ]
        for path in required:
            self.assertTrue(path.exists(), f"missing file: {path}")

    def test_model_declares_breeds(self) -> None:
        code = model_code_only()
        self.assertIn("breed [ dispatchers dispatcher ]", code)
        self.assertIn("breed [ incidents incident ]", code)
        self.assertIn("breed [ responders responder ]", code)

    def test_model_contains_required_procedures(self) -> None:
        code = model_code_only()
        procedures = [
            "to setup",
            "to setup-llm",
            "to setup-dispatchers",
            "to setup-responders",
            "to go",
            "to triage-my-incidents",
            "to route-my-incidents",
            "to process-active-cases",
            "to dispatcher-reflect",
            "to handle-episode-boundary",
        ]
        for proc in procedures:
            self.assertIn(proc, code, f"missing procedure: {proc}")

    def test_model_uses_llm_config_and_template(self) -> None:
        code = model_code_only()
        self.assertIn('set config-path "demos/crisis-triage/config.txt"', code)
        self.assertIn('set triage-template-path "demos/crisis-triage/triage-template.yaml"', code)
        self.assertIn("llm:chat-with-template triage-template-path", code)

    def test_model_uses_all_eight_primitives(self) -> None:
        code = model_code_only()
        primitives = [
            "llm:load-config",
            "llm:set-history",
            "llm:chat-with-template",
            "llm:choose",
            "llm:history",
            "llm:chat",
            "llm:clear-history",
            "llm:active",
        ]
        for prim in primitives:
            self.assertIn(prim, code, f"missing LLM primitive: {prim}")

    def test_triage_template_placeholders_match_model(self) -> None:
        template = read(TRIAGE_TEMPLATE_PATH)
        placeholders = set(re.findall(r"\{([a-zA-Z_][a-zA-Z0-9_]*)\}", template))
        self.assertEqual(
            placeholders,
            {"persona", "episode", "tick", "incident", "impact"},
        )

    def test_config_has_required_keys(self) -> None:
        config = parse_config(CONFIG_PATH)
        for key in ["provider", "model", "temperature", "max_tokens", "timeout_seconds"]:
            self.assertIn(key, config, f"missing key in config: {key}")

    def test_config_max_tokens_is_200(self) -> None:
        config = parse_config(CONFIG_PATH)
        self.assertEqual(config["max_tokens"], "200")

    def test_readme_has_core_sections(self) -> None:
        readme = read(README_PATH)
        for text in [
            "Quick Start",
            "A/B Experiment",
            "Design Rationale",
            "Paper Connection",
        ]:
            self.assertIn(text, readme)


class TestModelXmlParsing(unittest.TestCase):
    def setUp(self) -> None:
        self.root = parse_model()

    def test_model_parses_as_valid_xml(self) -> None:
        self.assertEqual(self.root.tag, "model")

    def test_code_element_contains_cdata_content(self) -> None:
        code_elem = self.root.find("code")
        self.assertIsNotNone(code_elem, "missing <code> element")
        self.assertIsNotNone(code_elem.text, "<code> element has no text content")
        self.assertIn("extensions [ llm ]", code_elem.text)

    def test_raw_file_preserves_cdata_wrapping(self) -> None:
        raw = read(MODEL_PATH)
        self.assertIn("<code><![CDATA[", raw)
        self.assertIn("]]></code>", raw)

    def test_widgets_section_has_expected_children(self) -> None:
        widgets = self.root.find("widgets")
        self.assertIsNotNone(widgets, "missing <widgets> section")
        child_tags = [child.tag for child in widgets]
        self.assertIn("view", child_tags)
        self.assertIn("button", child_tags)
        self.assertIn("monitor", child_tags)
        self.assertIn("switch", child_tags)
        self.assertIn("chooser", child_tags)
        self.assertIn("slider", child_tags)
        self.assertIn("plot", child_tags)

    def test_widgets_button_count(self) -> None:
        widgets = self.root.find("widgets")
        buttons = widgets.findall("button")
        self.assertEqual(len(buttons), 4, "expected 4 buttons: setup, go, add-incident, force-reflect")

    def test_widgets_monitor_count(self) -> None:
        widgets = self.root.find("widgets")
        monitors = widgets.findall("monitor")
        self.assertGreaterEqual(len(monitors), 12, "expected at least 12 monitors")

    def test_widgets_plot_count(self) -> None:
        widgets = self.root.find("widgets")
        plots = widgets.findall("plot")
        self.assertEqual(len(plots), 2, "expected 2 plots: Accuracy Over Time, Case Flow")

    def test_turtle_shapes_defined(self) -> None:
        shapes = self.root.find("turtleShapes")
        self.assertIsNotNone(shapes, "missing <turtleShapes> section")
        shape_names = [s.get("name") for s in shapes.findall("shape")]
        self.assertIn("default", shape_names)
        self.assertIn("circle", shape_names)
        self.assertIn("person", shape_names)


class TestModelStructure(unittest.TestCase):
    def setUp(self) -> None:
        self.root = parse_model()

    def test_netlogo_version_is_7_0_3(self) -> None:
        version = self.root.get("version")
        self.assertEqual(version, "NetLogo 7.0.3")

    def test_required_top_level_sections_exist(self) -> None:
        required_sections = [
            "code", "widgets", "info", "turtleShapes", "linkShapes",
            "previewCommands",
        ]
        present = {child.tag for child in self.root}
        for section in required_sections:
            self.assertIn(section, present, f"missing top-level section: {section}")

    def test_info_section_not_empty(self) -> None:
        info = self.root.find("info")
        self.assertIsNotNone(info, "missing <info> section")
        self.assertTrue(
            info.text and len(info.text.strip()) > 0,
            "<info> section is empty",
        )

    def test_preview_commands_present(self) -> None:
        preview = self.root.find("previewCommands")
        self.assertIsNotNone(preview)
        self.assertIn("setup", preview.text)

    def test_link_shapes_has_default(self) -> None:
        link_shapes = self.root.find("linkShapes")
        self.assertIsNotNone(link_shapes, "missing <linkShapes>")
        names = [s.get("name") for s in link_shapes.findall("shape")]
        self.assertIn("default", names)


class TestBehaviorRegression(unittest.TestCase):
    def setUp(self) -> None:
        self.code = model_code_only()

    def test_extensions_declaration_present(self) -> None:
        self.assertIn("extensions [ llm ]", self.code)

    def test_chat_with_template_uses_list_syntax(self) -> None:
        lines = self.code.splitlines()
        for line in lines:
            stripped = line.strip()
            if "llm:chat-with-template" not in stripped:
                continue
            self.assertNotRegex(
                stripped,
                r'llm:chat-with-template\s+\S+\s+\[\[',
                f"bracket syntax found instead of (list ...): {stripped}",
            )

    def test_no_inline_provider_setup_in_procedures(self) -> None:
        for deprecated in ["llm:set-provider", "llm:set-api-key", "llm:set-model"]:
            self.assertNotIn(
                deprecated,
                self.code,
                f"deprecated inline primitive found: {deprecated}",
            )

    def test_all_procedure_blocks_are_closed(self) -> None:
        opens = len(re.findall(r"^to(?:-report)?\s", self.code, re.MULTILINE))
        closes = len(re.findall(r"^end\s*$", self.code, re.MULTILINE))
        self.assertEqual(
            opens,
            closes,
            f"mismatched procedure blocks: {opens} opens vs {closes} ends",
        )

    def test_no_deprecated_primitives(self) -> None:
        deprecated = [
            "llm:ask",
            "llm:send",
            "llm:query",
            "llm:prompt",
        ]
        for prim in deprecated:
            self.assertNotIn(prim, self.code, f"deprecated primitive: {prim}")

    def test_globals_declared(self) -> None:
        self.assertIn("globals [", self.code)
        for g in ["llm-ready?", "config-path", "triage-template-path",
                   "incident-bank", "total-triaged", "correct-triage"]:
            self.assertIn(g, self.code, f"missing global: {g}")

    def test_incident_bank_has_30_entries(self) -> None:
        """The incident bank should contain 30 incidents (10 misleading + 10 clear + 10 borderline)."""
        code = self.code
        # Count (list " patterns inside build-incident-bank — each incident starts with (list "
        bank_start = code.find("to build-incident-bank")
        bank_end = code.find("\nend", bank_start)
        bank_code = code[bank_start:bank_end]
        incident_count = bank_code.count('(list "')
        # The outer (list wrapping all incidents doesn't start with (list "
        self.assertEqual(incident_count, 30, f"expected 30 incidents, found {incident_count}")


if __name__ == "__main__":
    unittest.main()
