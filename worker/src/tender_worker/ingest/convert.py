"""DOCX to PDF with LibreOffice (AI 3.1).

One conversion at a time per call, in a fresh profile directory with macros off, killed after
`timeout` seconds. The viewer shows the PDF this produces, so the rendering and the passages agree.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import shutil
import signal
import tempfile
from pathlib import Path

from tender_worker.ingest.model import IngestFailure

# MacroSecurityLevel 3 is "very high": only macros from trusted locations run, and there are none.
_PROFILE = """<?xml version="1.0" encoding="UTF-8"?>
<oor:items xmlns:oor="http://openoffice.org/2001/registry"
 xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<item oor:path="/org.openoffice.Office.Common/Security/Scripting">
<prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item>
<item oor:path="/org.openoffice.Office.Common/Security/Scripting">
<prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop></item>
</oor:items>
"""

_FILTER = 'pdf:writer_pdf_Export:{"ExportBookmarks":{"type":"boolean","value":"true"}}'


async def docx_to_pdf(data: bytes, *, timeout: float = 300.0, soffice: str | None = None) -> bytes:
    binary = soffice or shutil.which("soffice") or shutil.which("libreoffice")
    if binary is None:
        raise IngestFailure("other", "The document converter is not installed", retryable=False)
    work = Path(tempfile.mkdtemp(prefix="convert-"))
    try:
        source = work / "input.docx"
        source.write_bytes(data)
        profile = work / "profile"
        (profile / "user").mkdir(parents=True)
        (profile / "user" / "registrymodifications.xcu").write_text(_PROFILE)
        out = work / "out"
        out.mkdir()
        proc = await asyncio.create_subprocess_exec(
            binary,
            "--headless",
            "--norestore",
            "--nolockcheck",
            "--nodefault",
            "--nologo",
            f"-env:UserInstallation=file://{profile}",
            "--convert-to",
            _FILTER,
            "--outdir",
            str(out),
            str(source),
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
            env={"HOME": str(work), "PATH": os.environ.get("PATH", ""), "LANG": "C.UTF-8"},
            start_new_session=True,
        )
        try:
            await asyncio.wait_for(proc.wait(), timeout)
        except TimeoutError:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(proc.pid, signal.SIGKILL)
            raise IngestFailure(
                "other", "The document took too long to convert", retryable=True
            ) from None
        result = out / "input.pdf"
        if proc.returncode != 0 or not result.exists():
            raise IngestFailure("unsupported_format", "The document could not be converted")
        return result.read_bytes()
    finally:
        shutil.rmtree(work, ignore_errors=True)


def has_tracked_changes(data: bytes) -> bool:
    """True when the DOCX holds unresolved insertions or deletions (AI 3.1)."""
    import io
    import zipfile

    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            xml = z.read("word/document.xml")
    except Exception:  # noqa: BLE001 - not a zip: the converter will say so
        return False
    return b"<w:ins " in xml or b"<w:del " in xml
