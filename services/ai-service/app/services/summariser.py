"""
Clinical note summarisation.

Two paths, always both available. When a Claude API key is configured the note is summarised by
the model into the structured shape a clinician reads. When it is not — local development, CI, an
air-gapped deployment, or an API outage — a deterministic extractive summariser answers instead.
The response says which one ran, because a clinician should know whether a model or a rule
produced what they are reading.
"""

from __future__ import annotations

import logging
import re

from app.config import Settings
from app.schemas import NoteSummary
from app.services.negation import asserted

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """You summarise clinical notes for the clinician who will read the chart next.

Rules:
- Use only what the note states. Never infer a diagnosis, a medication, or a result that is not written.
- Preserve numbers, units and laterality exactly as recorded.
- If a section has nothing in the note, return it empty rather than inventing content.
- red_flags lists only findings already in the note that warrant urgent attention. It is not a
  differential diagnosis and not advice.
- Write in the clipped register of a handover, not prose."""

# Headings clinicians actually write, mapped to the section they belong to.
_SECTION_PATTERNS: dict[str, tuple[str, ...]] = {
    "presenting_complaint": ("presenting complaint", "chief complaint", "c/o", "complains of",
                             "reason for visit", "subjective"),
    "assessment": ("assessment", "impression", "diagnosis", "provisional diagnosis"),
    "plan": ("plan", "management", "advice", "treatment", "rx"),
    "follow_up": ("follow up", "follow-up", "review", "next visit", "recall"),
    "key_findings": ("examination", "o/e", "on examination", "objective", "findings",
                     "investigations", "vitals"),
}

# Phrases in a note that a reader must not scroll past. Deliberately conservative: a false red
# flag costs attention, and too many would train clinicians to ignore the field.
_RED_FLAG_TERMS: tuple[str, ...] = (
    "chest pain", "shortness of breath", "breathlessness", "haemoptysis", "hemoptysis",
    "melaena", "melena", "haematemesis", "hematemesis", "syncope", "loss of consciousness",
    "seizure", "sudden onset", "worst headache", "neck stiffness", "photophobia",
    "focal neurological", "slurred speech", "facial droop", "weight loss", "night sweats",
    "suicidal", "self harm", "self-harm", "anaphylaxis", "stridor", "cyanosis",
    "severe bleeding", "unresponsive", "sepsis",
)


def _split_sentences(text: str) -> list[str]:
    parts = re.split(r"(?<=[.!?])\s+|\n+", text)
    return [part.strip() for part in parts if part.strip()]


def _find_sections(note: str) -> dict[str, list[str]]:
    """
    Pulls labelled sections out of a note.

    Clinical notes are usually semi-structured — a heading, a colon, then content — so the
    fallback reads that structure rather than guessing from position.
    """
    found: dict[str, list[str]] = {key: [] for key in _SECTION_PATTERNS}
    for raw_line in note.splitlines():
        line = raw_line.strip(" -\t")
        if not line:
            continue
        lowered = line.lower()
        for section, headings in _SECTION_PATTERNS.items():
            for heading in headings:
                if lowered.startswith(heading):
                    content = line[len(heading):].lstrip(" :-\t")
                    if content:
                        found[section].append(content)
                    break
    return found


def extractive_summary(note: str) -> NoteSummary:
    """
    The deterministic fallback.

    It extracts and never generates: every string it returns is copied from the note. That is the
    right trade-off when no model is available — a shorter, duller summary is safe, whereas
    fabricating clinical content is not.
    """
    sections = _find_sections(note)
    sentences = _split_sentences(note)

    # Only flags the note actually asserts. A substring match reports "chest pain" on a note that
    # says "no chest pain" - the exact opposite of what the clinician wrote, and precisely the
    # kind of false alarm that teaches people to stop reading the field.
    red_flags = sorted({term for term in _RED_FLAG_TERMS if asserted(term, note)})

    complaint = sections["presenting_complaint"][0] if sections["presenting_complaint"] else (
        sentences[0] if sentences else ""
    )
    assessment = " ".join(sections["assessment"])[:500]
    plan = [item for line in sections["plan"] for item in re.split(r";|,(?=\s*[A-Za-z]{3})", line)]
    plan = [item.strip() for item in plan if item.strip()][:8]

    findings = [item.strip() for item in sections["key_findings"] if item.strip()][:8]
    if not findings:
        # Fall back to sentences carrying a measurement, which is where findings usually live.
        findings = [s for s in sentences if re.search(r"\d", s)][:5]

    headline = " ".join(sentences[:2])[:400] if sentences else ""
    return NoteSummary(
        summary=headline,
        presenting_complaint=complaint[:300],
        key_findings=findings,
        assessment=assessment,
        plan=plan,
        follow_up=(sections["follow_up"][0][:200] if sections["follow_up"] else ""),
        red_flags=red_flags,
    )


def _build_prompt(note: str, age: int | None, sex: str | None, encounter_type: str | None) -> str:
    context: list[str] = []
    if age is not None:
        context.append(f"Age: {age}")
    if sex:
        context.append(f"Sex: {sex}")
    if encounter_type:
        context.append(f"Encounter type: {encounter_type}")
    header = ("\n".join(context) + "\n\n") if context else ""
    return f"{header}Clinical note:\n\n{note}"


def summarise_with_model(note: str, settings: Settings, age: int | None = None,
                         sex: str | None = None, encounter_type: str | None = None) -> NoteSummary:
    """
    Summarises via the Claude API.

    Raises on any failure so the caller can fall back — a summarisation outage must degrade the
    feature, never the encounter.
    """
    import anthropic

    client = anthropic.Anthropic(api_key=settings.anthropic_api_key)
    response = client.messages.parse(
        model=settings.summary_model,
        max_tokens=settings.summary_max_tokens,
        system=SYSTEM_PROMPT,
        # Adaptive thinking, at an effort below the default: a clinician is waiting, and the note
        # is already in front of them.
        thinking={"type": "adaptive"},
        output_config={"effort": settings.summary_effort},
        messages=[{"role": "user", "content": _build_prompt(note, age, sex, encounter_type)}],
        output_format=NoteSummary,
    )

    # Safety classifiers can decline; that is a 200 response, not an exception.
    if getattr(response, "stop_reason", None) == "refusal":
        category = getattr(getattr(response, "stop_details", None), "category", None)
        raise RuntimeError(f"Model declined to summarise this note (category: {category})")

    parsed = response.parsed_output
    if parsed is None:
        raise RuntimeError("Model returned no parseable summary")
    return parsed
