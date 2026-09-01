"""
Negation detection for clinical free text.

Clinicians write what they ruled out as often as what they found. "Sore throat, no fever" and
"no chest pain" are assertions of absence, and any keyword matcher that ignores them reports the
opposite of what the note says. In triage that inflates acuity; in a note summary it puts a red
flag on a symptom the clinician explicitly excluded. Both erode trust in the output, and an alert
nobody believes is worse than no alert.

This is a deliberately small, explainable rule rather than a model. A negation classifier that is
right 95% of the time is harder to justify to a clinician than a cue list they can read - and when
this is wrong, it is wrong in a way someone can point at and fix.
"""

from __future__ import annotations

import re

#: Cues that negate what follows them. Kept to unambiguous ones: "low" and "denies any" style
#: hedges are not here, because a hedge is not an exclusion.
NEGATION_CUES = re.compile(
    r"\b(no|not|without|denies|denied|denying|nil|negative for|absent|free of|rules? out)\b"
)

#: How far back to look for a cue. Long enough for "no history of chest pain", short enough that a
#: cue in an unrelated earlier clause does not suppress a real finding.
NEGATION_WINDOW = 30


def is_negated(text: str, match_start: int) -> bool:
    """
    Whether the text immediately before ``match_start`` negates the term found there.

    A clause boundary between the cue and the term ends the negation's scope, so
    "no fever. severe chest pain" does not negate the chest pain.
    """
    window_start = max(0, match_start - NEGATION_WINDOW)
    preceding = text[window_start:match_start]
    last_boundary = max(preceding.rfind("."), preceding.rfind(";"), preceding.rfind(" but "))
    if last_boundary != -1:
        preceding = preceding[last_boundary + 1:]
    return bool(NEGATION_CUES.search(preceding))


def asserted(term: str, text: str) -> bool:
    """
    Whether ``text`` actually asserts ``term``, rather than ruling it out.

    True if at least one occurrence is un-negated: a note that says "no chest pain on arrival,
    now with severe chest pain" is asserting chest pain.
    """
    lowered = text.lower()
    pattern = re.escape(term.lower())
    matches = list(re.finditer(pattern, lowered))
    if not matches:
        return False
    return any(not is_negated(lowered, match.start()) for match in matches)
