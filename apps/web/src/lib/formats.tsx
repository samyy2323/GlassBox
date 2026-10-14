import { BookOpen, Braces, FileCode, FileSpreadsheet, FileText, Presentation, Table2, type LucideIcon } from 'lucide-react'

export interface FormatInfo {
  label: string
  icon: LucideIcon
}

// One icon per kind of file, all in the theme's own colours: the shape and the label tell file types apart, not hue.
const WORD: FormatInfo = { label: 'Word', icon: FileText }
const SHEET: FormatInfo = { label: 'Spreadsheet', icon: FileSpreadsheet }
const SLIDES: FormatInfo = { label: 'Slides', icon: Presentation }

const FORMATS: Record<string, FormatInfo> = {
  PDF: { label: 'PDF', icon: FileText },
  DOCX: WORD,
  DOC: WORD,
  XLSX: SHEET,
  XLS: SHEET,
  PPTX: SLIDES,
  PPT: SLIDES,
  CSV: { label: 'Table', icon: Table2 },
  MD: { label: 'Markdown', icon: FileText },
  TXT: { label: 'Text', icon: FileText },
  HTML: { label: 'HTML', icon: FileCode },
  XML: { label: 'XML', icon: FileCode },
  JSON: { label: 'JSON', icon: Braces },
  EPUB: { label: 'E-book', icon: BookOpen },
  ODF: { label: 'OpenDocument', icon: FileText },
  RTF: { label: 'Rich text', icon: FileText },
}

export function formatOf(sourceType: string): FormatInfo {
  return FORMATS[sourceType] ?? { label: sourceType, icon: FileText }
}

/** What the user can drop in; shown under every upload area. */
export const SUPPORTED_SUMMARY = 'PDF · Word · Excel · PowerPoint · Markdown · HTML · CSV · JSON · XML · EPUB · OpenDocument · RTF · text and code'
export const NOT_SUPPORTED_NOTE = 'Images, audio and video are not supported (they need OCR or transcription).'
export const MAX_FILE_BYTES = 25 * 1024 * 1024
