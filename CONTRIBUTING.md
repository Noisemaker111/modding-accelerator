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
2. **[Issues](https://github.com/Noisemaker111/modding-accelerator/issues) and
   [pull requests](https://github.com/Noisemaker111/modding-accelerator/pulls)**, open and closed. Someone may already
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

## What every change is for: compounding tooling

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

- **One goal, one PR, however long the work takes.** A session spent making one mod faster, or moving porting
  forward, ends in one PR, not a string of PRs along the way. Open it as a draft when you start, push every step to
  it, and mark it ready when the goal is met and proven. Open a second PR only for a separate goal someone could
  merge or reject on its own. A tool you built along the way belongs in the same PR as the work that needed it.
- Use the PR template, including its **Carries over** line: what the next mod gets for free from your work.
- Prove it the way a user would see it, at the smallest real scope. `AGENTS.md` has the commands. A change that can't
  be run in the game says so plainly instead of claiming it works.
- Keep tests few and meaningful: each proves something users depend on and fails on a wrong answer.
- Merge only when every required check is green.
- A setup or tooling trap you solved goes in `KNOWN_ISSUES.md`.

## What to bring to the maker

Almost nothing. Bring only what you can't settle from the code, docs, issues and PRs: legal or terms doubt,
anything that needs an account, secret or money, anything that publishes outside this repo, or two decisions that
conflict. Open an issue (preferred), or message [@noisemakerjon on X](https://x.com/noisemakerjon).
