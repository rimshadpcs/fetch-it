// Identify = Gemini 3.5 Flash-Lite raced against Claude Haiku 5.5, both started at t=0.
// Gemini's answer is accepted the moment it arrives (best boxes + Channel3 matches in the Oct 23 benchmark);
// the backup's only after a 3 s grace or once Gemini failed. The backup is Claude (no errors, 4.3 s worst case,
// where Flash-Lite had 11 s outliers and, on Oct 23, a 503 "high demand" outage); if Claude fails or isn't
// configured, Gemini 3.6 Flash takes its place. Low confidence → one re-ask of Gemini 3.6 Flash.
import { identifyWithClaude } from "./claude.js";
import { callGemini, FLASH_LITE, STRONG, type AttemptTrace, type DetectedItem, type GeminiModel, type IdentifyResult } from "./gemini.js";
import { racePreferred } from "./race.js";

export const CLAUDE_GRACE_MS = 3000;
/** Below this, ask the strong model again (its answer replaces the weak one if it succeeds). */
export const LOW_CONFIDENCE = 0.6;

export interface IdentifyKeys {
  gemini: string;
  /** Without it, identify runs Gemini alone. */
  anthropic?: string;
}

export async function identify(keys: IdentifyKeys, jpeg: Buffer): Promise<IdentifyResult> {
  const t0 = performance.now();
  const trace: AttemptTrace[] = [];

  /** Run one attempt, recording it in the trace whatever happens. */
  const attempt = async <T extends { items: DetectedItem[] }>(
    model: string,
    role: AttemptTrace["role"],
    signal: AbortSignal | undefined,
    run: () => Promise<T & { usage?: Partial<AttemptTrace> }>,
  ): Promise<{ items: DetectedItem[]; model: string }> => {
    const entry: AttemptTrace = { model, role, startedAtMs: Math.round(performance.now() - t0), ms: null, outcome: "lost" };
    trace.push(entry);
    const began = performance.now();
    try {
      const r = await run();
      Object.assign(entry, { ms: Math.round(performance.now() - began), outcome: "ok", ...(r.usage ?? {}) });
      return { items: r.items, model };
    } catch (e) {
      Object.assign(entry, {
        ms: Math.round(performance.now() - began),
        outcome: signal?.aborted ? "lost" : "error",
        status: (e as { status?: number }).status,
      });
      throw e;
    }
  };
  const gemini = (m: GeminiModel, role: AttemptTrace["role"]) => (signal?: AbortSignal) =>
    attempt(m.model, role, signal, () => callGemini(keys.gemini, jpeg, m, signal));
  const claude = (signal: AbortSignal) =>
    attempt("claude-haiku-5-5", "race", signal, async () => {
      const r = await identifyWithClaude(keys.anthropic!, jpeg, undefined, signal);
      return { items: r.items, usage: { promptTokens: r.trace[0]?.promptTokens, outputTokens: r.trace[0]?.outputTokens } };
    });
  const strongFallback = gemini(STRONG, "fallback");
  // The backup slot: Claude from t=0; if Claude fails, 3.6 Flash takes over the slot. With no Claude key,
  // 3.6 Flash starts only when the grace runs out (a hedge), so a healthy Flash-Lite costs one call.
  const backup = keys.anthropic
    ? async (signal: AbortSignal) => {
        try {
          return await claude(signal);
        } catch (e) {
          if (signal.aborted) throw e;
          return strongFallback(signal);
        }
      }
    : async (signal: AbortSignal) => {
        await sleep(CLAUDE_GRACE_MS, signal);
        return strongFallback(signal);
      };

  let { value: winner } = await racePreferred(gemini(FLASH_LITE, "primary"), backup, CLAUDE_GRACE_MS);

  // Not sure what it is? Ask the strongest fast Gemini once. Keep the first answer if that fails.
  const top = Math.max(0, ...winner.items.map((i) => i.confidence));
  if (!winner.items.length || top < LOW_CONFIDENCE) {
    try {
      const strong = await gemini(STRONG, "low_confidence")();
      if (strong.items.length) winner = strong;
    } catch {
      // traced; keep what we had
    }
  }
  return { ...winner, trace };
}

/** setTimeout as a promise that rejects when `signal` aborts (so a hedge that's no longer needed never starts). */
function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(signal.reason);
    const t = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => (clearTimeout(t), reject(signal.reason)), { once: true });
  });
}
