import { LogoMark } from './LogoMark'

/** The mark and the name. The name is set in the interface face, tight, with the second half a touch lighter. */
export function Wordmark({ size = 26 }: { size?: number }) {
  return (
    <span className="inline-flex items-center gap-2.5">
      <LogoMark size={size} title="Glassbox" />
      <span className="text-[1.08rem] font-semibold tracking-[-0.03em]">
        Glass<span className="font-normal text-muted">box</span>
      </span>
    </span>
  )
}
