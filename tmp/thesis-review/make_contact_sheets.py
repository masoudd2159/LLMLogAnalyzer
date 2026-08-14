from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(r"D:\Programming\Thesis\LLMLogAnalyzer\tmp\thesis-review")


def make(folder: str, chunk: int = 12, thumb_width: int = 360) -> None:
    base = ROOT / folder
    files = sorted(base.glob("page-*.png"))
    for start in range(0, len(files), chunk):
        group = files[start : start + chunk]
        thumbs = []
        for path in group:
            with Image.open(path) as im:
                ratio = thumb_width / im.width
                thumb = im.convert("RGB").resize((thumb_width, int(im.height * ratio)))
                canvas = Image.new("RGB", (thumb_width, thumb.height + 28), "white")
                canvas.paste(thumb, (0, 28))
                ImageDraw.Draw(canvas).text((8, 6), path.stem, fill="black")
                thumbs.append(canvas)
        cols = 3
        rows = (len(thumbs) + cols - 1) // cols
        cell_h = max(t.height for t in thumbs)
        sheet = Image.new("RGB", (cols * thumb_width, rows * cell_h), "#d8d8d8")
        for idx, thumb in enumerate(thumbs):
            sheet.paste(thumb, ((idx % cols) * thumb_width, (idx // cols) * cell_h))
        sheet.save(base / f"contact-{start + 1:03d}-{start + len(group):03d}.jpg", quality=88)


def make_named_sample() -> None:
    base = ROOT / "sample"
    files = sorted(base.glob("*.png"))
    for start in range(0, len(files), 12):
        group = files[start : start + 12]
        thumbs = []
        for path in group:
            with Image.open(path) as im:
                ratio = 360 / im.width
                thumb = im.convert("RGB").resize((360, int(im.height * ratio)))
                canvas = Image.new("RGB", (360, thumb.height + 28), "white")
                canvas.paste(thumb, (0, 28))
                ImageDraw.Draw(canvas).text((8, 6), path.stem, fill="black")
                thumbs.append(canvas)
        rows = (len(thumbs) + 2) // 3
        cell_h = max(t.height for t in thumbs)
        sheet = Image.new("RGB", (1080, rows * cell_h), "#d8d8d8")
        for idx, thumb in enumerate(thumbs):
            sheet.paste(thumb, ((idx % 3) * 360, (idx // 3) * cell_h))
        sheet.save(base / f"contact-sample-{start + 1:03d}-{start + len(group):03d}.jpg", quality=88)


for name in ("main", "guideline", "proposal"):
    make(name)
make_named_sample()
