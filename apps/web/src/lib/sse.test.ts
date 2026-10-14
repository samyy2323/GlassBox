import { describe, expect, it } from 'vitest'
import { SseParser } from './sse'

describe('SseParser', () => {
  it('parses complete frames', () => {
    const p = new SseParser()
    expect(p.push('event:token\ndata:{"t":"Hi"}\n\nevent:done\ndata:{}\n\n')).toEqual([
      { event: 'token', data: '{"t":"Hi"}' },
      { event: 'done', data: '{}' },
    ])
  })

  it('holds a partial frame until the blank line arrives, wherever the chunk boundary falls', () => {
    const stream = 'event:token\ndata:{"t":"abc"}\n\nevent:done\ndata:{"ok":true}\n\n'
    for (let cut = 1; cut < stream.length; cut++) {
      const p = new SseParser()
      const events = [...p.push(stream.slice(0, cut)), ...p.push(stream.slice(cut))]
      expect(events).toEqual([
        { event: 'token', data: '{"t":"abc"}' },
        { event: 'done', data: '{"ok":true}' },
      ])
    }
  })

  it('handles CRLF line endings, including a split between CR and LF', () => {
    const p = new SseParser()
    const a = p.push('event: token\r\ndata: x\r')
    const b = p.push('\n\r\n')
    expect([...a, ...b]).toEqual([{ event: 'token', data: 'x' }])
  })

  it('joins multi-line data and strips one leading space only', () => {
    expect(new SseParser().push('data: line one\ndata:  indented\n\n')).toEqual([{ event: 'message', data: 'line one\n indented' }])
  })

  it('ignores comments and keep-alives', () => {
    const p = new SseParser()
    expect(p.push(': keepalive\n\n')).toEqual([])
    expect(p.push(':ping\n\nevent:ready\ndata:{}\n\n')).toEqual([{ event: 'ready', data: '{}' }])
  })

  it('accepts fields without a space after the colon and defaults the event name', () => {
    expect(new SseParser().push('data:{"a":1}\n\n')).toEqual([{ event: 'message', data: '{"a":1}' }])
  })

  it('does not emit an unfinished trailing frame', () => {
    const p = new SseParser()
    expect(p.push('event:token\ndata:{"t":"partial"}')).toEqual([])
    expect(p.push('\n\n')).toEqual([{ event: 'token', data: '{"t":"partial"}' }])
  })
})
