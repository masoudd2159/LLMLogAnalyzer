from pathlib import Path
import sys
from PIL import Image, ImageDraw

base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r"D:\Programming\Thesis\LLMLogAnalyzer\tmp\thesis-working\render")
files = sorted(base.glob("page-*.png"))
for start in range(0, len(files), 9):
    group = files[start:start + 9]
    thumbs = []
    for path in group:
        with Image.open(path) as image:
            ratio = 380 / image.width
            thumb = image.convert("RGB").resize((380, int(image.height * ratio)))
        canvas = Image.new("RGB", (380, thumb.height + 28), "white")
        canvas.paste(thumb, (0, 28))
        ImageDraw.Draw(canvas).text((8, 6), path.stem, fill="black")
        thumbs.append(canvas)
    rows = (len(thumbs) + 2) // 3
    cell_h = max(t.height for t in thumbs)
    sheet = Image.new("RGB", (1140, rows * cell_h), "#d8d8d8")
    for idx, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((idx % 3) * 380, (idx // 3) * cell_h))
    sheet.save(base / f"contact-{start + 1:03d}-{start + len(group):03d}.jpg", quality=90)
