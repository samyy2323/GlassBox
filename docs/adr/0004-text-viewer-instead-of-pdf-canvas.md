# ADR 0004: X-Ray shows extracted text, not a rendered PDF page

Status: accepted (2026-10-14).

## Context

X-Ray must open a cited passage in its document and highlight it exactly (SPEC 10). The spec suggests react-pdf. Chunks, gold spans
and citations are all character ranges of the normalized text that the parser extracted, and for a PDF that text is not the PDF's own
text layer: we join lines into paragraphs, collapse whitespace and drop empty blocks.

## Decision

The document viewer renders that extracted text with page markers (`document_versions.page_starts`) and highlights character ranges
directly. A cited passage is marked exactly because it is the same string and the same offsets retrieval used.

## Consequences

- Always exact, no matching step that can drift, and the same component serves PDF, DOCX, Markdown and HTML.
- Users do not see the original page layout, figures or tables as drawn. Fixing that would mean mapping our offsets onto pdf.js text-layer
  items, with fuzzy matching where our normalization differs; revisit if users ask for it.
- The original file is still stored and can be downloaded later.
