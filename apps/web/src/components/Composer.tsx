import { forwardRef, useEffect, useImperativeHandle, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowUp, Paperclip, Square } from 'lucide-react'

export interface ComposerHandle {
  focus: () => void
}

interface Props {
  busy: boolean
  placeholder?: string
  /** Question typed back into the box (for example after the server asked to slow down). */
  restore?: string
  onSend: (text: string) => void
  onStop: () => void
  onAttach: (files: File[]) => void
}

const MAX_CHARS = 2000

/** The floating message box: grows with the text, Enter sends, Shift+Enter adds a line, the paperclip uploads documents. */
export const Composer = forwardRef<ComposerHandle, Props>(function Composer({ busy, placeholder, restore, onSend, onStop, onAttach }, ref) {
  const [text, setText] = useState('')
  const area = useRef<HTMLTextAreaElement>(null)
  const files = useRef<HTMLInputElement>(null)

  useImperativeHandle(ref, () => ({ focus: () => area.current?.focus() }))

  useEffect(() => {
    if (restore) setText(restore)
  }, [restore])

  // Auto-grow up to about eight lines.
  useEffect(() => {
    const el = area.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`
  }, [text])

  function send() {
    const q = text.trim()
    if (!q || busy) return
    onSend(q)
    setText('')
  }

  function onKey(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      send()
    }
  }

  const near = text.length > MAX_CHARS - 200
  return (
    <div className="glass-strong group mx-auto flex w-full max-w-[46rem] items-end gap-1.5 rounded-[1.75rem] p-2 transition-shadow focus-within:shadow-[0_0_0_1.5px_rgb(var(--accent-rgb)/0.55),0_18px_60px_-18px_var(--glow)]">
      <button type="button" className="btn-ghost !rounded-full !p-2.5" onClick={() => files.current?.click()} aria-label="Upload documents" title="Upload documents">
        <Paperclip className="h-5 w-5" />
      </button>
      <input
        ref={files}
        type="file"
        multiple
        className="sr-only"
        tabIndex={-1}
        onChange={(e) => {
          if (e.target.files?.length) onAttach([...e.target.files])
          e.target.value = ''
        }}
      />
      <label htmlFor="composer" className="sr-only">Your question</label>
      <textarea
        id="composer"
        ref={area}
        rows={1}
        maxLength={MAX_CHARS}
        value={text}
        placeholder={placeholder ?? 'Ask anything about your documents…'}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKey}
        className="max-h-[200px] min-h-[2.75rem] flex-1 resize-none bg-transparent px-1 py-2.5 text-[0.95rem] leading-6 text-fg outline-none placeholder:text-muted"
      />
      {near && <span className="self-center pr-1 text-xs text-muted tabular-nums">{text.length}/{MAX_CHARS}</span>}
      {busy ? (
        <button type="button" onClick={onStop} aria-label="Stop generating" title="Stop"
          className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-fg text-canvas shadow-lg transition hover:scale-105">
          <Square className="h-4 w-4 fill-current" />
        </button>
      ) : (
        <button type="button" onClick={send} disabled={!text.trim()} aria-label="Send question" title="Send (Enter)"
          className="btn-primary !h-11 !w-11 !rounded-full !p-0">
          <ArrowUp className="h-5 w-5" strokeWidth={2.5} />
        </button>
      )}
    </div>
  )
})
