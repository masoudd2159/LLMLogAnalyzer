from __future__ import annotations

import os
import subprocess
from pathlib import Path

from pypdf import PdfReader, PdfWriter


base = Path(os.environ["RENDER_DIR"])
parts = sorted(base.glob("part-*.pdf"))
if not parts:
    raise RuntimeError("No PDF parts were produced")

writer = PdfWriter()
counts = []
for part in parts:
    reader = PdfReader(part)
    counts.append(len(reader.pages))
    for page in reader.pages:
        writer.add_page(page)

merged = base / "working-final.pdf"
with merged.open("wb") as stream:
    writer.write(stream)

poppler = Path(
    r"C:\Users\Masoud\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\poppler\Library\bin\pdftoppm.exe"
)
subprocess.run(
    [str(poppler), "-png", "-r", "120", str(merged), str(base / "page")],
    check=True,
)
print(merged)
print("parts", counts, "total", sum(counts))
