// k6 Counter sums signed samples. Evaluate this balance only at the end of a run.
export function trackJourney(unfinished, run) {
  unfinished.add(1);
  run();
  // Deliberately not in finally: an interrupted/throwing iteration must remain outstanding.
  unfinished.add(-1);
}
