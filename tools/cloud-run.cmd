@echo off
rem Heavy jobs run on GitHub, not this machine: see tools\cloud-run.py.
python "%~dp0cloud-run.py" %*
