import { FileText, ListChecks, Lightbulb, Search, type LucideIcon } from 'lucide-react'

export interface Suggestion {
  icon: LucideIcon
  /** Small label above the card text. */
  kind: string
  /** What the card says. */
  title: string
  /** What is actually asked. */
  prompt: string
}

/**
 * Starter questions built from the user's own document titles, so they are relevant and one click away.
 *
 * The wording is deliberate. Measured against llama3.2:3b, "Summarize the document X" made the model refuse (it is not a
 * question the passages answer), while "What are the main points of the X document?" and "What should I know about X?"
 * were answered with citations. See benchmarks/prompt_check.py for how answer prompts are checked.
 */
export function suggestionsFor(titles: string[]): Suggestion[] {
  const unique = [...new Set(titles)]
  const a = unique[0]
  const b = unique[1] ?? unique[0]
  const c = unique[2] ?? unique[1] ?? unique[0]
  const out: Suggestion[] = []
  if (a) out.push({ icon: FileText, kind: 'Overview', title: `Main points of “${a}”`, prompt: `What are the main points of the "${a}" document?` })
  if (b) out.push({ icon: ListChecks, kind: 'Facts', title: `Key facts and numbers in “${b}”`, prompt: `What are the key facts, numbers and deadlines in "${b}"?` })
  if (c) out.push({ icon: Lightbulb, kind: 'Explore', title: `What to know about “${c}”`, prompt: `What should I know about "${c}"?` })
  out.push({ icon: Search, kind: 'Find', title: 'Which document mentions time limits?', prompt: 'Which of my documents mention deadlines or time limits?' })
  return out.slice(0, 4)
}
