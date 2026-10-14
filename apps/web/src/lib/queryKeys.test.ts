import { describe, expect, it } from 'vitest'
import { queryKeys } from './queryKeys'

describe('queryKeys', () => {
  it('gives every differently shaped query its own root key', () => {
    const roots = [
      queryKeys.workspaces()[0],
      queryKeys.documents('w')[0],
      queryKeys.documentContent('d')[0],
      queryKeys.recentTraces('w')[0],
      queryKeys.traceList('w')[0],
      queryKeys.trace('t')[0],
    ]
    expect(new Set(roots).size).toBe(roots.length)
  })

  it('keeps the sidebar history and the X-Ray list apart even for the same workspace', () => {
    expect(queryKeys.recentTraces('w')).not.toEqual(queryKeys.traceList('w'))
  })

  it('scopes workspace data by workspace id', () => {
    expect(queryKeys.documents('a')).not.toEqual(queryKeys.documents('b'))
    expect(queryKeys.recentTraces('a')).not.toEqual(queryKeys.recentTraces('b'))
  })
})
