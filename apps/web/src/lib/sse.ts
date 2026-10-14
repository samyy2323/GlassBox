// Incremental parser for text/event-stream. fetch() delivers arbitrary byte chunks, so a frame can arrive split
// anywhere (even between "\r" and "\n"); the parser buffers until a blank line completes a frame.

export interface SseEvent {
  event: string
  data: string
}

export class SseParser {
  private buffer = ''

  push(chunk: string): SseEvent[] {
    let text = this.buffer + chunk
    // A trailing "\r" might be the first half of "\r\n": hold it back until the next chunk.
    let tail = ''
    if (text.endsWith('\r')) {
      tail = '\r'
      text = text.slice(0, -1)
    }
    text = text.replace(/\r\n?/g, '\n')
    const frames = text.split('\n\n')
    this.buffer = (frames.pop() ?? '') + tail

    const events: SseEvent[] = []
    for (const frame of frames) {
      const ev = parseFrame(frame)
      if (ev) events.push(ev)
    }
    return events
  }
}

function parseFrame(frame: string): SseEvent | null {
  let event = 'message'
  const data: string[] = []
  let sawField = false
  for (const line of frame.split('\n')) {
    if (line === '' || line.startsWith(':')) continue // blank or comment (keep-alive)
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') {
      event = value
      sawField = true
    } else if (field === 'data') {
      data.push(value)
      sawField = true
    }
  }
  return sawField ? { event, data: data.join('\n') } : null
}
