**One sentence: what a modaccel user can do now, or what stopped going wrong.**

<!--
Rules, then delete this comment:
- Title: what changed for the user, in plain words. No lists joined by commas; if the title needs
  "and", it is two PRs (a long feature PR lists its parts under "Parts").
- Write for someone who knows Minecraft modding but not this code. No class names in the first two
  sections unless the user types them.
- Every claim in "Checked" names the command you ran and what decided the pass. A check that would
  still pass with the change missing does not count.
- Delete a section that does not apply. Keep this body true: when a later commit changes the PR,
  update the body in the same push.
-->

Closes #

🔍 **Where it showed up:** the mod, command or CI run where you saw the problem.

❌ **Before**
1. Run `modaccel …` on …
2. ❌ What went wrong, quoted from the output.

✅ **After**
1. Same command.
2. What happens now, quoted from the output.

💡 **Why it happened:** the cause in one or two sentences, and why the fix removes it rather than hiding it.

### Parts
<!-- Only for a long feature PR. One line each, newest last; tick when the part is checked. -->
- [ ] Part: one line on what it does.

### Checked
| What | Command | Result |
|---|---|---|
| The fix | `modaccel …` on a fresh clone of … | passed; failed before the change |

Cache: cold (fresh clone, empty caches) or warm. Systems: Windows / Linux / macOS.

### Not covered
What this does not handle, and what is unverified.

👀 **Read first:** the file or two a reviewer should open; the rest is routine.
