# core/learning

Pure Kotlin (JVM) learning engine for Steady, package `app.steady.learning`. No Android dependencies.

- `Card`, `ReviewState`, `Rating` — flashcards and their review schedule (`@Serializable`, instants as ISO-8601).
- `Scheduler` — small FSRS-inspired scheduler. First GOOD ≈ 1 day, second ≈ 3 days, HARD grows less,
  AGAIN brings the card back in 10 minutes and counts a lapse. Difficulty 1..10, intervals capped at 365 days.
  Formulas are in the KDoc of `Scheduler.kt`.
- `RecallToCards` — turns the recall lines written after a focus block into cards. `A = B`, `A: B`, `A - B`,
  `A is B` become front/back (earliest separator wins); other lines become a cloze hiding the longest word
  of 5+ letters. Lines under 4 characters are skipped. Unicode is kept (`I = I_cm + Md²`).
- `SwapPicker` — picks the card for a 60-second swap: most overdue first, otherwise the newest card.
- `FocusSession`, `WeeklyGoal`, `GoalProgress` — flexible weekly goal ("N days this week"). A local day counts
  when it has at least `minMinutes` of focus (sessions count toward their start day). No streaks, ever.
  `remainingDays = max(0, targetDays - doneDays.size)`.

Everything uses `java.time`. `app.steady.learning.serial.InstantSerializer` is a temporary local copy;
it will be replaced by `app.steady.rules.serial.InstantSerializer` from `core/rules` at integration (Phase 12).

## Tests

```
cd android && ./gradlew :core:learning:build --console=plain
```

JUnit 5 tests live in `src/test/kotlin/app/steady/learning/`.
