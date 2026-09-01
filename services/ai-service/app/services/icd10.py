"""
ICD-10 code suggestion.

TF-IDF retrieval over a bundled subset, with character n-grams alongside word n-grams so a
misspelling or a clinical abbreviation still finds its code. Retrieval rather than generation is
deliberate: a suggested code must exist in the classification, and a model asked to produce codes
freely will occasionally invent a plausible-looking one that does not.

Suggestions are ranked, never applied. A coder or clinician accepts one.
"""

from __future__ import annotations

import json
import logging
import pathlib
import re

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import linear_kernel

from app.schemas import CodeSuggestion

logger = logging.getLogger(__name__)


class Icd10Index:
    """A searchable index over the ICD-10 subset."""

    def __init__(self, codes: list[dict]) -> None:
        self._codes = codes
        # Each code's searchable text is its description plus every clinical synonym, with the
        # description repeated so an exact description match outranks a synonym match.
        corpus = [
            f"{entry['description']} {entry['description']} {' '.join(entry.get('terms', []))}"
            for entry in codes
        ]
        # Two views of the text: words catch clinical phrases, character n-grams catch typos and
        # abbreviations ("diabetis", "sob").
        self._word_vectorizer = TfidfVectorizer(
            analyzer="word", ngram_range=(1, 2), sublinear_tf=True, min_df=1, stop_words="english"
        )
        self._char_vectorizer = TfidfVectorizer(
            analyzer="char_wb", ngram_range=(3, 5), sublinear_tf=True, min_df=1
        )
        self._word_matrix = self._word_vectorizer.fit_transform(corpus)
        self._char_matrix = self._char_vectorizer.fit_transform(corpus)

    @property
    def size(self) -> int:
        return len(self._codes)

    @classmethod
    def load(cls, path: str) -> Icd10Index:
        data = json.loads(pathlib.Path(path).read_text())
        codes = data["codes"]
        logger.info("Loaded %d ICD-10 codes from %s", len(codes), path)
        return cls(codes)

    def _matched_terms(self, entry: dict, query: str) -> list[str]:
        """Which of the code's own terms appear in the query, so a suggestion can be justified."""
        lowered = query.lower()
        matched = [
            term for term in [entry["description"], *entry.get("terms", [])]
            if term.lower() in lowered
        ]
        if matched:
            return matched[:4]
        # No whole-phrase hit: fall back to the significant words the two share.
        query_words = set(re.findall(r"[a-z]{4,}", lowered))
        candidate_words = {
            w for text in [entry["description"], *entry.get("terms", [])]
            for w in re.findall(r"[a-z]{4,}", text.lower())
        }
        return sorted(query_words & candidate_words)[:4]

    def suggest(self, query: str, limit: int = 5) -> list[CodeSuggestion]:
        if not query.strip():
            return []
        word_scores = linear_kernel(
            self._word_vectorizer.transform([query]), self._word_matrix
        ).ravel()
        char_scores = linear_kernel(
            self._char_vectorizer.transform([query]), self._char_matrix
        ).ravel()
        # Words carry the clinical meaning; character n-grams are the robustness term.
        combined = 0.7 * word_scores + 0.3 * char_scores

        ranked = combined.argsort()[::-1][: limit * 3]
        suggestions: list[CodeSuggestion] = []
        for index in ranked:
            score = float(combined[index])
            if score <= 0.01:
                continue
            entry = self._codes[index]
            suggestions.append(CodeSuggestion(
                code=entry["code"],
                description=entry["description"],
                score=round(min(score, 1.0), 4),
                matched_terms=self._matched_terms(entry, query),
            ))
            if len(suggestions) >= limit:
                break
        return suggestions
