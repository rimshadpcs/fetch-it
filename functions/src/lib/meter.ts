// Counts calls to paid APIs (Gemini, Claude, Channel3 search). The lib clients call meter.record(); the functions
// plug in the Firestore-backed daily counter (limits.ts). Spikes and benchmarks keep the no-op.

export type Service = "gemini" | "claude" | "channel3";

export const meter: { record: (service: Service) => void } = { record: () => {} };
