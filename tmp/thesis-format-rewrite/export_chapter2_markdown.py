from __future__ import annotations

import html
import re
import zipfile
from pathlib import Path

from docx import Document
from docx.oxml.ns import qn
from docx.table import Table
from docx.text.paragraph import Paragraph
from lxml import etree


ROOT = Path(r"D:\Programming\Thesis\LLMLogAnalyzer")
SOURCE = ROOT / "documents" / "پایان نامه ی مسعود دباغی - نسخه کاری.docx"
OUTPUT = ROOT / "documents" / "فصل دوم - نسخه Markdown.md"

W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS = {"w": W_NS}


def normalize_text(text: str) -> str:
    text = text.replace("\u00a0", " ").replace("\r", "")
    text = text.replace("حافظهٔ کوتاه‌مدت بلند", "حافظهٔ طولانی کوتاه‌مدت")
    text = re.sub(r"[ \t]+\n", "\n", text)
    return text.strip()


def node_text(node: etree._Element, footnote_ids: set[int]) -> str:
    parts: list[str] = []

    def walk(current: etree._Element) -> None:
        if current.tag == qn("w:t"):
            parts.append(current.text or "")
            return
        if current.tag == qn("w:tab"):
            parts.append("\t")
            return
        if current.tag in {qn("w:br"), qn("w:cr")}:
            parts.append("\n")
            return
        if current.tag == qn("w:footnoteReference"):
            note_id = int(current.get(qn("w:id")))
            footnote_ids.add(note_id)
            parts.append(f"[^{note_id}]")
            return
        if current.tag in {qn("w:instrText"), qn("w:delText")}:
            return
        for child in current:
            walk(child)

    walk(node)
    return normalize_text("".join(parts))


def paragraph_to_markdown(paragraph: Paragraph, footnote_ids: set[int]) -> str:
    text = node_text(paragraph._p, footnote_ids)
    if not text:
        return ""

    style = paragraph.style.name if paragraph.style is not None else ""
    if style == "Heading 1":
        return f"# {text}"
    if style == "Heading 2":
        return f"## {text}"
    if style == "Heading 3":
        return f"### {text}"
    if style.startswith("Caption"):
        return f"**{text}**"
    if text.startswith("Accuracy =") or text.startswith("Precision ="):
        return f"`{text}`"
    return text


def cell_text(cell, footnote_ids: set[int]) -> str:
    paragraphs = [node_text(p._p, footnote_ids) for p in cell.paragraphs]
    value = "<br>".join(item for item in paragraphs if item)
    value = value.replace("|", "\\|")
    return value


def markdown_table(table: Table, footnote_ids: set[int]) -> str:
    rows = [[cell_text(cell, footnote_ids) for cell in row.cells] for row in table.rows]
    if not rows:
        return ""
    width = len(rows[0])
    output = [
        "| " + " | ".join(rows[0]) + " |",
        "| " + " | ".join(["---"] * width) + " |",
    ]
    output.extend("| " + " | ".join(row) + " |" for row in rows[1:])
    return "\n".join(output)


def relationship_figure(table: Table, footnote_ids: set[int]) -> str:
    rows = [[cell_text(cell, footnote_ids) for cell in row.cells] for row in table.rows]

    def td(value: str, *, header: bool = False, colspan: int | None = None) -> str:
        tag = "th" if header else "td"
        span = f' colspan="{colspan}"' if colspan else ""
        escaped = html.escape(value, quote=False).replace("\n", "<br>")
        return f"    <{tag}{span}>{escaped}</{tag}>"

    lines = ['<table dir="rtl">', "  <tr>", td(rows[0][0], header=True, colspan=4), "  </tr>"]
    lines.extend(
        [
            "  <tr>",
            td(rows[1][0], header=True),
            td(rows[1][1], header=True),
            td(rows[1][2], header=True, colspan=2),
            "  </tr>",
            "  <tr>",
            td(rows[2][0]),
            td(rows[2][1]),
            td(rows[2][2], colspan=2),
            "  </tr>",
            "  <tr>",
            td(rows[3][0], header=True),
            td(rows[3][1], header=True),
            td(rows[3][2], header=True, colspan=2),
            "  </tr>",
            "  <tr>",
            td(rows[4][0]),
            td(rows[4][1]),
            td(rows[4][2], colspan=2),
            "  </tr>",
            "  <tr>",
            td(rows[5][0], colspan=4),
            "  </tr>",
            "  <tr>",
            *(td(value, header=True) for value in rows[6]),
            "  </tr>",
            "  <tr>",
            *(td(value) for value in rows[7]),
            "  </tr>",
            "</table>",
        ]
    )
    result = "\n".join(lines)
    return result.replace("Logمدل زبانی بزرگ", "LogLLM").replace(
        "LogParser-مدل زبانی بزرگ", "LogParser-LLM"
    )


def read_footnotes() -> dict[int, str]:
    with zipfile.ZipFile(SOURCE) as archive:
        root = etree.fromstring(archive.read("word/footnotes.xml"))
    notes: dict[int, str] = {}
    for note in root.xpath("./w:footnote", namespaces=NS):
        note_id = int(note.get(qn("w:id")))
        if note_id < 1:
            continue
        text = normalize_text("".join(note.xpath(".//w:t/text()", namespaces=NS)))
        notes[note_id] = text
    return notes


def main() -> None:
    document = Document(SOURCE)
    footnote_ids: set[int] = set()
    pieces: list[str] = []
    started = False
    table_index = 0

    for block in document.iter_inner_content():
        if isinstance(block, Paragraph):
            text = node_text(block._p, footnote_ids if started else set())
            if not started:
                if block.style.name == "Heading 1" and text.startswith("فصل دوم:"):
                    started = True
                else:
                    continue
            rendered = paragraph_to_markdown(block, footnote_ids)
            if rendered:
                pieces.append(rendered)
        elif isinstance(block, Table):
            if not started:
                continue
            if table_index == 0:
                pieces.append(relationship_figure(block, footnote_ids))
            else:
                pieces.append(markdown_table(block, footnote_ids))
            table_index += 1

    if not started:
        raise RuntimeError("Chapter 2 heading was not found")
    if table_index != 3:
        raise RuntimeError(f"Expected 3 chapter tables, found {table_index}")

    footnotes = read_footnotes()
    chapter_notes = sorted(note_id for note_id in footnote_ids if note_id in footnotes)
    pieces.append("<!-- پاورقی‌های انگلیسی فصل دوم -->")
    pieces.extend(f"[^{note_id}]: {footnotes[note_id]}" for note_id in chapter_notes)

    content = "\n\n".join(pieces).rstrip() + "\n"
    OUTPUT.write_text(content, encoding="utf-8")
    print(OUTPUT)
    print(f"characters={len(content)} tables={table_index} footnotes={chapter_notes}")


if __name__ == "__main__":
    main()
