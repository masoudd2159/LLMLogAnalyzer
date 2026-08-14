from pathlib import Path

from pypdf import PdfReader, PdfWriter

base = Path(r"D:\Programming\Thesis\LLMLogAnalyzer\tmp\thesis-ch2\render3")
parts = [
    base / "part-01-10.pdf",
    base / "part-11-20.pdf",
    base / "part-21-30.pdf",
    base / "part-31-40.pdf",
]
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
