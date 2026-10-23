/**
 * Race a preferred and a backup producer, both started at t=0:
 * - the preferred result is accepted the moment it arrives;
 * - the backup result is accepted only once `graceMs` has passed since the start, or once the preferred one failed;
 * - if both fail, the preferred error is thrown.
 * The loser is aborted through its AbortSignal. Pure orchestration: no model or network code in here.
 */
export interface RaceOutcome<T> {
  value: T;
  winner: "preferred" | "backup";
  /** ms from start until the winning value was accepted (can be later than it arrived, for the backup). */
  acceptedAtMs: number;
}

export function racePreferred<T>(
  preferred: (signal: AbortSignal) => Promise<T>,
  backup: ((signal: AbortSignal) => Promise<T>) | null,
  graceMs: number,
  now: () => number = () => performance.now(),
): Promise<RaceOutcome<T>> {
  const start = now();
  const prefCtl = new AbortController();
  const backCtl = new AbortController();

  return new Promise<RaceOutcome<T>>((resolve, reject) => {
    let done = false;
    let preferredFailed = false;
    let preferredError: unknown;
    let backupFailed = !backup;
    let backupValue: { value: T } | null = null;
    let graceTimer: ReturnType<typeof setTimeout> | null = null;

    const finish = (value: T, winner: RaceOutcome<T>["winner"]) => {
      if (done) return;
      done = true;
      if (graceTimer) clearTimeout(graceTimer);
      (winner === "preferred" ? backCtl : prefCtl).abort();
      resolve({ value, winner, acceptedAtMs: Math.round(now() - start) });
    };
    const fail = () => {
      if (done || !preferredFailed || !backupFailed) return;
      done = true;
      if (graceTimer) clearTimeout(graceTimer);
      reject(preferredError);
    };
    const maybeAcceptBackup = () => {
      if (!backupValue) return;
      if (preferredFailed || now() - start >= graceMs) finish(backupValue.value, "backup");
    };

    preferred(prefCtl.signal).then(
      (v) => finish(v, "preferred"),
      (e) => {
        preferredFailed = true;
        preferredError = e;
        maybeAcceptBackup();
        fail();
      },
    );

    if (backup) {
      backup(backCtl.signal).then(
        (v) => {
          backupValue = { value: v };
          maybeAcceptBackup();
          // Arrived inside the grace window: accept it when the window closes, unless the preferred wins first.
          // The timer firing *is* the end of the window — don't re-check the clock: timers can fire a hair
          // early, and a failed re-check would leave the backup waiting on the preferred call's full timeout.
          if (!done && !graceTimer) {
            graceTimer = setTimeout(() => backupValue && finish(backupValue.value, "backup"), Math.max(0, graceMs - (now() - start)));
          }
        },
        () => {
          backupFailed = true;
          fail();
        },
      );
    }
  });
}
