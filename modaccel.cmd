@echo off
rem Compatibility launcher: modaccel is now mmt (Minecraft Modding Tools).
echo modaccel.cmd is now mmt.cmd (Minecraft Modding Tools): run mmt.cmd instead; this name will be removed. 1>&2
rem Started without call, so mmt.cmd takes over and its exit code is this command's; arguments pass through untouched.
"%~dp0mmt.cmd" %*
