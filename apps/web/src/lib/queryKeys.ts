// Every TanStack Query key in one place. Two queries that share a key share one cache entry, so they must return the same
// shape of data: the sidebar's plain "recent traces" page and the X-Ray list's infinite pages once collided and crashed the
// page. Give each differently shaped query its own root key here.

export const queryKeys = {
  workspaces: () => ['workspaces'] as const,
  documents: (workspaceId?: string) => ['documents', workspaceId] as const,
  documentContent: (documentId: string) => ['document-content', documentId] as const,
  /** The newest page of traces, for the sidebar history and the command palette. */
  recentTraces: (workspaceId?: string) => ['recent-traces', workspaceId] as const,
  /** The X-Ray list: infinite pages of traces. */
  traceList: (workspaceId?: string) => ['trace-list', workspaceId] as const,
  trace: (traceId: string) => ['trace', traceId] as const,
  testsets: (workspaceId?: string) => ['testsets', workspaceId] as const,
  testset: (id: string) => ['testset', id] as const,
  testsetQuestions: (id: string) => ['testset-questions', id] as const,
  experiments: (workspaceId?: string) => ['experiments', workspaceId] as const,
  experiment: (id: string) => ['experiment', id] as const,
  experimentResults: (id: string, run: string) => ['experiment-results', id, run] as const,
  runDiff: (a: string, b: string) => ['run-diff', a, b] as const,
  calibration: (workspaceId?: string) => ['calibration', workspaceId] as const,
  diagnosis: (experimentId: string, run: string) => ['diagnosis', experimentId, run] as const,
  feedbackList: (workspaceId?: string) => ['feedback-list', workspaceId] as const,
  feedbackSummary: (workspaceId?: string) => ['feedback-summary', workspaceId] as const,
  feedbackItem: (id: string) => ['feedback-item', id] as const,
  budget: (workspaceId?: string) => ['budget', workspaceId] as const,
  autotunes: (workspaceId?: string) => ['autotunes', workspaceId] as const,
  autotune: (id: string) => ['autotune', id] as const,
  testsetDiff: (id: string, against: string) => ['testset-diff', id, against] as const,
}
