# Contributing to mmt

Help is wanted, from people and from agents. You don't need permission to start: open a pull request, and if it needs
changing, it will be changed with you. The project is still finding its place, so most of it is open for you to shape.

## Before you contribute or ask anything

Check, in this order, and only then ask:

1. **The code.** `README.md` (what it does), `SCOPE.md` (what works where), `AGENTS.md` (how to work here).
2. **[`DECISIONS.md`](DECISIONS.md).** The few things the maker has fixed. Everything not written there is your call.
3. **[Issues](https://github.com/Noisemaker111/modding-accelerator/issues) and
   [pull requests](https://github.com/Noisemaker111/modding-accelerator/pulls)**, open and closed. Someone may already
   have the plan, the answer, or the same change in flight. Plans live there, not in docs.

## How much freedom you have

- **Do what you judge right for the project's current feel and scope.** The maker is not specific about
  implementation. If a choice is yours, make it, say why in the PR, and move on.
- **If you're unsure what the maker would pick, ask a close-to-frontier model for its opinion.** That is very close to
  how the maker decides, and it is faster than waiting for an answer. Put the question and the answer in the PR.
- **If the maker is specific about an implementation, it is written in `DECISIONS.md`.** A preference that isn't
  written there isn't a rule. When you settle a question others will hit again, add it there (one short entry).
- Disagree with something in `DECISIONS.md` or `AGENTS.md`? Open an issue with your reason; don't work around it.

## What is allowed in the repo

Do whatever the licences and terms of service allow, and nothing past them.

- **Other people's work is downloaded and derived on the user's machine; it is never committed or shipped.** That
  covers Mojang's game jars and mappings, the loaders' jars and mappings (Fabric, NeoForge, Forge, MCP names), and the
  mods or plugins we test. What is committed is our own: rules, tests, tooling and measurements.
- **Don't copy code from the mods we test into rules or fixtures.** Rules are written here, each with its own
  before/after test. A mod may be used as a fixture only if its licence lets us.
- **Download through a source's published interface** (Maven, its API, its release files) and within its terms.
  Don't scrape around a rule or a rate limit.
- **Contributions are under this repo's MIT licence.**
- **When unsure, don't commit it.** Derive it at run time, or open an issue.

## Making a change

- One feature, one branch, one PR. Commit as you go; push follow-ups to the same PR. Use the PR template.
- Prove it the way a user would see it, at the smallest real scope. `AGENTS.md` has the commands. A change that can't
  be run in the game says so plainly instead of claiming it works.
- Keep tests few and meaningful: each proves something users depend on and fails on a wrong answer.
- Merge only when every required check is green.
- A setup or tooling trap you solved goes in `KNOWN_ISSUES.md`.

## What to bring to the maker

Almost nothing. Bring only what you can't settle from the code, `DECISIONS.md`, issues and PRs: legal or terms doubt,
anything that needs an account, secret or money, anything that publishes outside this repo, or two decisions that
conflict. Open an issue (preferred), or message [@noisemakerjon on X](https://x.com/noisemakerjon).
