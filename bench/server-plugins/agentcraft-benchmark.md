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
| Original bot dependency install | 14.978 first install; 0.993 warm install | 8.957 install in separate checkout | Different sessions and dependency cache states. Not included in the suite totals. |
| Modaccel setup only | Not recorded | 55.631, previously reported | Setup is not a full build. |
| Original three bot cases, fresh server directories, warm dependencies | 194.810 | 236.910 | Same bot assertions, Paper build, Via plugins, heap size and main world settings. Different sessions, server lifecycle, and remaining configuration; modaccel total sums three commands and excludes gaps. |
| Server starts within the fresh-directory cases | 100.804 | 137.7 | Sums all six starts through server readiness; different sessions. |
| Original three bot cases, reused server files, warm dependencies | Not recorded | 159.802 | Same bot assertions; modaccel total includes a one-time 2.274s tool compilation and excludes gaps between commands. |
| Modaccel's separate three scenarios, reused server files | Not recorded | 123.090 | MCProtocolLib and different assertions: not the original suite. |

The original suite used Mineflayer on Minecraft 1.21.11 with ViaVersion and
ViaBackwards on Paper 26.3 build 223. The modaccel original-bot runs use that same
bot and its 61 assertions: only its Docker RCON and restart calls are adapted.
Every case uses a fresh world and restart. For the fresh-directory runs, Paper's
server files are also fresh; the Paper jar, Gradle dependencies, Java and Node
dependencies are cached. The modaccel runner starts the bot after the server is
ready; the original starts it concurrently and polls for readiness. Other server
settings, JVM options and Java installation may differ, so these historical runs
are not a controlled speedup comparison. The mock backend deliberately streams
each completed reply over roughly six seconds. Original Docker timings were not
recorded; the original comparison ran natively because Docker was unavailable.

The fallback case failed once at three short-lived bubble checks (17/20), then
passed unchanged twice. One fresh Hoplite case took 116.528 seconds, including
74.912 seconds of server starts; its log shows a long gap while loading a fresh
world and data, but does not isolate the cause. These results are retained, not
discarded as outliers.

For the new cold build, the Gradle distribution download, daemon launch and
metadata phase together took 47.130 seconds; the build phase took 14.001 seconds.
The combined phase does not isolate download from daemon startup. A test of
`-XX:TieredStopAtLevel=1` did not improve server startup, so it was not adopted.

Original logs: `C:\Users\Jk101\Projects\modding-accelerator\.runs\beta-trials\bench-logs\times.txt`.
New receipts are under the AgentCraft checkout's `.runs/portable/`:

- Cold build: `2026-10-28T04-19-18.200043200Z-3152bb0c/result.json`.
- Warm unit tests: `2026-10-28T04-34-56.745252500Z-1aa4e3dc/result.json`.
- Warm Capy, fallback and Hoplite scenarios respectively: `2026-10-28T04-45-34.197534900Z-2934ff00/result.json`, `2026-10-28T04-43-54.427415600Z-f7d7b066/result.json`, `2026-10-28T04-44-39.956834100Z-98ab48d6/result.json`.
- Original bot, reused server files, Capy / fallback / Hoplite: `2026-10-28T05-04-19.288175400Z-cb233e06/result.json`, `2026-10-28T05-05-26.714327200Z-8cd27b5a/result.json`, `2026-10-28T05-06-27.197821600Z-cbf18357/result.json`.
- Original bot, fresh server directories, Capy / fallback / Hoplite: `2026-10-28T05-07-26.275617100Z-fcd8d0db/result.json`, `2026-10-28T05-08-34.565093900Z-85381216/result.json`, `2026-10-28T05-09-54.305415800Z-39beab9a/result.json`.
- Failed fallback: `2026-10-28T05-02-38.264490500Z-0fd09586/result.json`.

To rerun the original bot, install the checkout's `test/e2e` Node dependencies,
then run `modaccel server-check` on each `agentcraft-original-*.scenario` with
`--server-build 223 --server-xms 2G` and both Via jars passed with `--plugins`.
Add `--fresh-server` for a fresh Paper directory. The adapter checks that exactly
two Docker transport expressions are present before changing them; it does not
rewrite the assertions. Receipts contain the server and external-test logs.

To establish a speedup, run the same assertions and client/server versions on
both paths, with matching fresh and warm cache conditions, and preserve the full
command outputs and timing receipts. First-use results should also disclose JDK,
server, Gradle and dependency cache states.
