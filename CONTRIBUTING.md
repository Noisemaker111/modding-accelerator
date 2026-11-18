# Contributing to mmt

**TL;DR: vibe away.** Use a strong, close-to-frontier model (not a weak one), check the code, issues and PRs first,
pick the best implementation you can, and open a PR. Don't ask permission. Whatever you fix, leave something the
next mod can reuse (below).

Help is wanted, from people and from agents. You don't need permission to start: open a pull request, and if it needs
changing, it will be changed with you. The project is still finding its place, so most of it is open for you to shape.

## Before you contribute or ask anything

Check, in this order, and only then ask:

1. **The code.** `README.md` (what it does and the rules it follows), `SCOPE.md` (what works where), `AGENTS.md` (how
   to work here). Anything the maker has fixed is written there or in the code. Anything not written is your call.
2. **[Issues](https://github.com/Noisemaker111/minecraft-modding-tools/issues) and
   [pull requests](https://github.com/Noisemaker111/minecraft-modding-tools/pulls)**, open and closed. Someone may already
   have the plan, the answer, or the same change in flight. Plans live there, not in docs.

## How much freedom you have

- **Do what you judge right for the project's current feel and scope.** The maker is not specific about
  implementation. If a choice is yours, make it, say why in the PR, and move on.
- **If you're unsure what the maker would pick, ask a close-to-frontier model for its opinion.** That is very close to
  how the maker decides, and it is faster than waiting for an answer. Put the question and the answer in the PR.
- **If the maker is specific about an implementation, it is written down** in `README.md`, `SCOPE.md`, `AGENTS.md`
  or the code. A preference that isn't written there isn't a rule.
- Disagree with something written there? Open an issue with your reason; don't work around it.

## What is allowed in the repo

Do whatever the licences and terms of service allow, and nothing past them.

- **Don't add third-party files to the repo.** That means Mojang's game jars and mappings, the loaders' jars and
  mapping files (Fabric, NeoForge, Forge, MCP names), and the mods or plugins we test. mmt downloads them from their
  publisher onto the user's machine, and doesn't put them in the repo or the release ZIP. You can commit data we derive
  from them (such as the name lists in `coverage/changes/`) if it says where it came from and keeps any notice the
  licence requires. Your own pull requests are always welcome; this only covers files that belong to someone else.
- **Don't copy code from the mods we test into rules or fixtures.** Rules are written here, each with its own
  before/after test. A mod may be used as a fixture only if its licence lets us.
- **Download through a source's published interface** (Maven, its API, its release files) and within its terms.
  Don't scrape around a rule or a rate limit.
- **Contributions are under this repo's MIT licence.**
- **When unsure, don't commit it.** Derive it at run time, or open an issue.

## How the work runs

The plan is the roadmap: `coverage/roadmap.json`, shown as `ROADMAP.md` and on the website. There is no other plan.
In November 2026 the method for porting, converting and pack speed was each worked out mid-session and kept only in
chat and in draft PRs that stayed open for days, so `main` went on teaching the method that had just been replaced
and every new session started from it. These four steps keep that from happening.

1. **Pick a roadmap step.** Take the goal's `doing` step, or its first `next`. Work that has no step gets one first,
   in the same PR. A step names its finish line before the work starts: one number, the value that ends the step,
   and the result that would prove the method wrong.
2. **Follow the goal's workflow.** Each kind of work has one written workflow, and it is the current method:

   | Work | Workflow | Whole-goal number |
   |---|---|---|
   | Try mmt on a new mod or plugin | `.claude/skills/bench-new-mod` | commands that pass on it, before/after loop time |
   | Port between game versions | `.claude/skills/port-a-mod` | most-downloaded mods with nothing left to port by hand, per update |
   | Convert between loaders | `.claude/skills/convert-a-mod` | most-downloaded mods that use nothing the bridge lacks |
   | Modpacks | `tools/packcheck/CONTRIBUTING.md` | packs that pass as shipped, and with repairs |
   | Make a mod faster | `.claude/skills/optimize-a-mod` | accepted speedups with exact gates, patterns saved |

3. **Choose the work from a list of what real mods use, and only once that list predicts the result.** The list comes
   from a source that names the whole job (what the 150 most-downloaded mods use of the game or of a loader, read
   from their jars in a minute or two with no build), ranked by how many mods each item frees. Before it chooses
   anything, check it against real builds: the mods it clears must build. On 2026-11-18 three methods were each
   picked from a list nobody had checked, and each missed (a new way to build ports freed 0 of 126; the port
   worklist cleared 21 ports and 7 built; three convert methods freed none of their targets). One item of the list
   is the unit of work, checked in minutes without porting or converting a mod; the sweep over our own corpus is
   the proof at the end of a PR, never what chooses the work. The third hand fix of the same kind still means stop
   and find what produces them.
4. **Land it at the finish line.** One step is one PR. When the number reaches the finish line and is proven, merge.
   The same PR marks the step done in `coverage/roadmap.json`, records the number, names the next step, and updates
   the workflow if the method changed, so `main` always holds the current method. A PR open for more than a few days
   had its finish line too far away: land what is proven and make the rest the next step.

## What every change is for: compounding tooling

The destination: someone drops a mod in, as its source folder or as its `.jar`, from the command line or the desktop
app, picks the Minecraft version and the loader they want, and gets it back the same way in minutes, so nobody has to
think about versions or loaders. It must work for any mod anyone makes, including ones we
have never seen, which rules out anything made by hand for one mod.

Whatever mod you work on, the real product is making the *next* mod better, even a totally different kind of mod.
A speedup to one mod, a port or a loader conversion is only half done until it leaves something behind for every
other mod:
- a general pattern in `knowledge/`;
- a tool, generator or script in `mmt`;
- a faster test loop;
- an update to a skill.

The aim is that any mod that has ever existed can be tested, measured and improved quickly and exactly. A fix that
only helps the mod it was made on is a partial result: say so, and say what would make it general.

## Making a change

- **One roadmap step, one PR.** Not a string of PRs along the way, and not one PR that never ends. Open it as a
  draft once there is a real result, push every commit of that step to it, and merge when its finish line is met
  and proven. A tool you built along the way belongs in the same PR as the work that needed it.
- **Title it like a line in the release notes, because it becomes one.** Say what a user can now do, in plain words,
  under about 50 characters: "Diorama builds maps 2-3x faster", "Convert more Fabric mods to NeoForge".
  - No round or batch numbers.
  - No lists of internals.
  - No tool or class names ("bridge mirrors", "store gate", "yarn").

  Numbers and details go in the description.
- Use the PR template. It keeps the latest state only (step, approach, number now); a description that grows a new
  "update" section each day is a log nobody can steer by. Its **Carries over** line says what the next mod gets for
  free from your work.
- Prove it the way a user would see it, at the smallest real scope. `AGENTS.md` has the commands. A change that can't
  be run in the game says so plainly instead of claiming it works.
- Keep tests few and meaningful: each proves something users depend on and fails on a wrong answer.
- Merge only when every required check is green.
- A setup or tooling trap you solved goes in `KNOWN_ISSUES.md`.

## Releases

Merging to `main` doesn't release anything. A release is a deliberate step, taken when there's something worth
handing to people, usually every few weeks: a goal that landed and was proven, or fixes users are waiting for.

- **One button:** Actions → Release → Run workflow, on `main`. It numbers the release, builds the command line and
  the desktop app (Windows and Linux) into one portable ZIP, tests that exact ZIP, tags the commit and publishes.
  Run it on any other branch to rehearse: everything but the tag and publish.
- **Notes are `.github/whats-new.md`:** a short, plain list of what people can now do. A PR that adds something users
  will notice adds a line there. The full list of merged PRs is folded underneath. The release refuses to publish
  when the file hasn't changed since the last release.
- **Version numbers are `0.MINOR.PATCH`.** Pick `minor` when people can do something new, `patch` for fixes only.
  A checkout says `dev`; only the Release workflow stamps a number. `1.0` is the maker's call.
- **Publishing is the maker's decision:** agents can rehearse a release but don't run it on `main` unasked.

## What to bring to the maker

Almost nothing. Bring only what you can't settle from the code, docs, issues and PRs: legal or terms doubt,
anything that needs an account, secret or money, anything that publishes outside this repo, or two decisions that
conflict. Open an issue (preferred), or message [@noisemakerjon on X](https://x.com/noisemakerjon).
