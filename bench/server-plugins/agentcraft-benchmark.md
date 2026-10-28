# AgentCraft benchmark evidence

Measurements below are observations, not speedup claims: the original and modaccel
runs differ in their commands, bot, server build, or assertion coverage. Times are
wall-clock seconds. All runs used AgentCraft commit
`eca1d9165de1c0e0e58c3e7cad26bb9a8a405ab4` on Windows.

| Work and cache state | Original | Modaccel | Comparability |
| --- | ---: | ---: | --- |
| Full build, fresh Gradle home; JDK already installed | 52.909 | 61.353 | Different sessions; original disabled the Gradle daemon, modaccel used it. Not a controlled pair. |
| Unit tests, warm dependencies | 8.272–11.320 | 2.443 | Original disabled the daemon; modaccel used it. |
| Edit to jar, warm dependencies | 9.427 without daemon; 1.944–2.212 with daemon | 1.8, previously reported | Original and modaccel measurements were not a controlled pair. |
| Three end-to-end bot scenarios, warm dependencies, fresh worlds | 194.810 | 123.090 | Different bot protocol, Paper build and checks; not the same suite. Modaccel total sums three command receipts and excludes gaps between commands. |
| Server startup within end-to-end scenarios | 95.681 | See individual receipts | Original value sums startup and restart for all three scenarios; no paired measurement. |

The original suite used Mineflayer on Minecraft 1.21.11 with ViaVersion and
ViaBackwards on Paper 26.3 build 223. It made 61 assertions across three cases,
each with a fresh world and restart. The new scenarios use MCProtocolLib 26.3-2
on Paper 26.3 build 224 and cover the principal behavior with different assertions;
they do not establish exact equivalence. The mock backend deliberately streams
each completed reply over roughly six seconds. Original Docker timings were not
recorded; the original comparison ran natively because Docker was unavailable.

For the new cold build, the Gradle distribution download, daemon launch and
metadata phase together took 47.130 seconds; the build phase took 14.001 seconds.
The combined phase does not isolate download from daemon startup. A test of
`-XX:TieredStopAtLevel=1` did not improve server startup, so it was not adopted.

Original logs: `C:\Users\Jk101\Projects\modding-accelerator\.runs\beta-trials\bench-logs\times.txt`.
New receipts are under the AgentCraft checkout's `.runs/portable/`:

- Cold build: `2026-10-28T04-19-18.200043200Z-3152bb0c/result.json`.
- Warm unit tests: `2026-10-28T04-34-56.745252500Z-1aa4e3dc/result.json`.
- Warm Capy, fallback and Hoplite scenarios respectively: `2026-10-28T04-45-34.197534900Z-2934ff00/result.json`, `2026-10-28T04-43-54.427415600Z-f7d7b066/result.json`, `2026-10-28T04-44-39.956834100Z-98ab48d6/result.json`.

To establish a speedup, run the same assertions and client/server versions on
both paths, with matching fresh and warm cache conditions, and preserve the full
command outputs and timing receipts. First-use results should also disclose JDK,
server, Gradle and dependency cache states.
