# Crisis Triage Demo Tests

Run from repository root:

```bash
python -m unittest discover -s demos/crisis-triage/tests -p "test_*.py" -v
```

These tests validate (29 tests, no API calls):

- Presence of all required demo files
- Breed declarations (dispatchers, incidents, responders)
- Required procedures (setup, triage, routing, reflection, episode boundary)
- All 8 LLM primitives present in code
- Template placeholder consistency with model substitutions
- Config key completeness and max_tokens=200
- README documentation sections
- XML structure (widgets, shapes, plots, CDATA)
- Incident bank has 30 entries (10 misleading + 10 clear + 10 borderline)
- Procedure block matching (every `to` has an `end`)
