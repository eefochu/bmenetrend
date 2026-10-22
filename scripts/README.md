# BMenetrend timetable maintenance

All three operators have reviewed PDF imports. See [ViaBusz import instructions](../docs/VIABUSZ.md), [HOMM import instructions](../docs/HOMM.md), and the KanyarGO procedure below.

## KanyarGO

These tools reproduce the BMenetrend native data asset from the operator's two reviewed PDFs and official map page. They are import tools for the exact reviewed document layouts, not generic PDF parsers. A changed source must be reviewed visually before updating the import or Kotlin source fingerprints.

Use Python 3.10+ in a virtual environment with `pdfplumber` and `beautifulsoup4` installed. `pdftoppm` (Poppler) is useful for visual comparison. No Android runtime or API key is needed for importing.

From the Android project's root directory:

```bash
python3 -m venv .venv-import
.venv-import/bin/pip install pdfplumber beautifulsoup4
mkdir -p scripts/raw
curl --fail --location https://kanyargo.paty.hu/Terkep --output scripts/raw/kanyargo-map.html
curl --fail --location https://kanyargo.paty.hu/Menetrend --output scripts/raw/kanyargo-timetable.html
curl --fail --location https://kanyargo.paty.hu/documents/menetrend-KanyarGO-2026-szept-1-tol.pdf --output scripts/raw/kanyargo-2026-school.pdf
curl --fail --location https://kanyargo.paty.hu/documents/menetrend-KanyarGO-2026-aug-10-tanszuneti.pdf --output scripts/raw/kanyargo-break.pdf
.venv-import/bin/python scripts/extract_kanyargo_school.py
.venv-import/bin/python scripts/extract_kanyargo_break.py
.venv-import/bin/python scripts/build_kanyargo_snapshot.py
```

All tools accept `--source-dir /absolute/path/to/source-files` instead of the default `scripts/raw`. Intermediate JSON files are written there. The final builder writes `app/src/main/assets/kanyargo-network.json` and its matching unit-test fixture. All paths are relative to the tool/project location, so no development-workspace path is required.

The reviewed baseline produces 37 KanyarGO stop IDs, 8 route labels, 34 direction variants, 37 school-term segments, and 19 school-break segments. Six school-term segments are restricted school services. The final importer prints the two SHA-256 values; compare them with `KanyargoParser.SCHOOL_SHA` and `BREAK_SHA`. Do not update those constants without reviewing the new PDFs and changing the layout importer as necessary.

Render both PDFs and compare every row/column after any import change:

```bash
pdftoppm -scale-to 2000 -png -singlefile scripts/raw/kanyargo-2026-school.pdf scripts/raw/school-review
pdftoppm -scale-to 2000 -png -singlefile scripts/raw/kanyargo-break.pdf scripts/raw/break-review
./gradlew testDebugUnitTest
```

The school term/break calendar is separate in `KanyargoCalendar.kt`; it must be reviewed against the official school calendar and annual working-day transfers. Known PDF changes disable KanyarGO timetable countdowns until reviewed. Live GPS remains independent. See [ADATFORRAS.md](../docs/ADATFORRAS.md) for detailed data limitations.
