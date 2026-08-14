from pathlib import Path

from pypdf import PdfReader, PdfWriter

base = Path(r"D:\Programming\Thesis\LLMLogAnalyzer\tmp\thesis-ch2\render-all27")
parts = [base / f"part-{a:02d}-{b:02d}.pdf" for a, b in [(1, 10), (11, 20), (21, 30), (31, 40), (41, 50)]]
writer = PdfWriter()
counts = []
for part in parts:
    reader = PdfReader(part)
    counts.append(len(reader.pages))
    for page in reader.pages:
        writer.add_page(page)
output = base / "working.pdf"
with output.open("wb") as stream:
    writer.write(stream)
print(output)
print("part_pages", counts, "total", sum(counts))
