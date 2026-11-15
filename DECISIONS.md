# Decisions

What the maker has fixed. Anything not here is the contributor's call (see [CONTRIBUTING.md](CONTRIBUTING.md)).
One entry each: the decision, why, and when. Add one when you settle something others will hit again; change or delete
one only with the maker's say, in an issue.

## Work is proven in the game
A mod counts as working only when the game shows it working: its own state asserted in a real client, a plugin on a
real server, a modpack's world made and reopened. A build or a load is a step, not a result.
Why: a green build proves nothing about behaviour. (From the start; see `README.md`.)

## No AI at run time
mmt makes no AI or network-model calls. Mappings and rules are deterministic data in `knowledge/`; agents write
them, the tool only reads them.
Why: results must be reproducible and checkable. (From the start; see `README.md`.)

## Rules are our own
Each rule is written here with a before/after test. Rules are never taken from other people's mods.
Why: it keeps the work ours to license and ours to prove. (From the start; see `README.md`.)

## Use only what licences and terms allow — 2026-11-15
Other people's work (game jars, mappings, loader files, mods) is downloaded from its publisher on the user's machine
and derived there. It is not committed and not in the release ZIP. When unsure, derive at run time or ask in an issue.
Why: the maker wants everything the project does to be allowed under the licences and terms involved.

## Contributors have free rein — 2026-11-15
Contributors choose their own implementation to fit the project's current scope, and may ask a close-to-frontier
model for its opinion where the maker's would do. Check code, decisions, issues and PRs first. Bring the maker only
what those can't settle, by issue or X.
Why: the project is finding its place; every PR is welcome and can be changed after.
