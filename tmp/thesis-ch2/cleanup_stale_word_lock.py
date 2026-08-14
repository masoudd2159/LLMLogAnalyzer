from pathlib import Path

lock = Path(r"D:\Programming\Thesis\LLMLogAnalyzer\documents\~$یان نامه ی مسعود دباغی - نسخه کاری.docx")
expected_parent = Path(r"D:\Programming\Thesis\LLMLogAnalyzer\documents").resolve()

if lock.resolve().parent != expected_parent:
    raise RuntimeError("Unexpected lock-file parent")
if lock.exists():
    if not lock.name.startswith("~$") or lock.stat().st_size > 1024:
        raise RuntimeError("Refusing to delete unexpected file")
    lock.unlink()
    print("removed", lock)
else:
    print("no stale lock")
