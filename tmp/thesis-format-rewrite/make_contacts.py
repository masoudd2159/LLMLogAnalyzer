import os
from pathlib import Path

from PIL import Image, ImageDraw


render_dir = Path(os.environ["RENDER_DIR"])
pages = sorted(render_dir.glob("page-*.png"))
per_sheet = 9
thumb_width = 390
label_height = 30
gap = 18

for start in range(0, len(pages), per_sheet):
    selected = pages[start : start + per_sheet]
    thumbs = []
    for page in selected:
        with Image.open(page) as source:
            ratio = thumb_width / source.width
            thumb = source.convert("RGB").resize(
                (thumb_width, round(source.height * ratio)), Image.Resampling.LANCZOS
            )
        thumbs.append((page, thumb))

    thumb_height = max(image.height for _, image in thumbs)
    sheet = Image.new(
        "RGB",
        (3 * thumb_width + 4 * gap, 3 * (thumb_height + label_height) + 4 * gap),
        "#d9d9d9",
    )
    draw = ImageDraw.Draw(sheet)

    for index, (page, thumb) in enumerate(thumbs):
        row, column = divmod(index, 3)
        x = gap + column * (thumb_width + gap)
        y = gap + row * (thumb_height + label_height + gap)
        sheet.paste(thumb, (x, y + label_height))
        draw.text((x, y + 5), page.stem, fill="black")

    first = start + 1
    last = start + len(selected)
    output = render_dir / f"contact-{first:03d}-{last:03d}.jpg"
    sheet.save(output, quality=90)
    print(output)
