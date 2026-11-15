//! Runs the mmt command line as a subprocess. The app never repeats the CLI's own logic: it
//! starts `mmt.cmd` (Windows) or `mmt` (macOS, Linux), which find or download Java the way
//! they do in a terminal, asks for `--json`, and reads the one JSON answer printed at the end.
//!
//! While a command runs it reports progress on stderr when `MMT_PROGRESS` is set
//! (`mmt-progress {"step": .., "log": ..}`), and each step writes its output to a log file:
//! a worker thread tails that file so the window shows the build and game output as it happens.

use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::Value;

/// Lines of live output kept for the window.
const KEEP_LINES: usize = 400;

/// Where the mmt launcher is, and how to start it.
#[derive(Clone, Debug)]
pub struct Launcher {
    /// The folder with the launcher, and either `mmt.jar` (a release) or `tools/cli` (a source checkout).
    pub home: PathBuf,
    /// `mmt.cmd` or `mmt`.
    pub program: PathBuf,
}

impl Launcher {
    /// Looks in `MMT_HOME` (or the old `MODACCEL_HOME`), then beside this program, then in the folders
    /// above it (so a development build in `app/target/release` finds the checkout it was built from).
    pub fn find() -> Result<Launcher, String> {
        let mut candidates = Vec::new();
        for variable in ["MMT_HOME", "MODACCEL_HOME"] {
            if let Some(home) = std::env::var_os(variable) {
                candidates.push(PathBuf::from(home));
            }
        }
        if let Ok(exe) = std::env::current_exe() {
            let mut dir = exe.parent().map(Path::to_path_buf);
            for _ in 0..5 {
                let Some(d) = dir else { break };
                candidates.push(d.clone());
                dir = d.parent().map(Path::to_path_buf);
            }
        }
        for home in &candidates {
            if let Some(launcher) = Launcher::at(home) {
                return Ok(launcher);
            }
        }
        Err(format!(
            "Couldn't find the mmt launcher ({}). Keep this program in the folder you extracted the download into, \
             or set MMT_HOME to that folder.",
            LAUNCHER
        ))
    }

    fn at(home: &Path) -> Option<Launcher> {
        let program = home.join(LAUNCHER);
        let runnable = home.join("mmt.jar").is_file() || home.join("tools/cli/Bootstrap.java").is_file();
        (program.is_file() && runnable).then(|| Launcher {
            home: dunce(home),
            program: dunce(&program),
        })
    }
}

#[cfg(windows)]
const LAUNCHER: &str = "mmt.cmd";
#[cfg(not(windows))]
const LAUNCHER: &str = "mmt";

/// `\\?\C:\x` is `C:\x`: cmd.exe and Java don't take verbatim paths.
pub fn dunce(path: &Path) -> PathBuf {
    let text = path.to_string_lossy();
    match text.strip_prefix(r"\\?\") {
        Some(rest) if !rest.starts_with("UNC") => PathBuf::from(rest),
        _ => path.to_path_buf(),
    }
}

/// What the subprocess left when it ended.
#[derive(Clone, Debug)]
pub struct Outcome {
    pub exit: Option<i32>,
    /// The `--json` answer, when the command printed one.
    pub json: Option<Value>,
    /// The command's own words when there was no JSON (a launcher that couldn't find Java).
    pub text: String,
    pub cancelled: bool,
}

impl Outcome {
    pub fn ok(&self) -> bool {
        !self.cancelled && self.exit == Some(0) && self.json.as_ref().is_some_and(|j| j["ok"].as_bool() != Some(false))
    }

    /// The command's `result` object (or the whole answer when it has none).
    pub fn result(&self) -> &Value {
        match &self.json {
            Some(j) if j.get("result").is_some() => &j["result"],
            Some(j) => j,
            None => &Value::Null,
        }
    }

    pub fn receipt(&self) -> Option<&str> {
        self.json.as_ref().and_then(|j| j["receipt"].as_str())
    }

    pub fn seconds(&self) -> Option<f64> {
        self.json.as_ref().and_then(|j| j["seconds"].as_f64())
    }

    /// The plainest summary the answer has: the CLI's error or reason when it failed, else its details.
    pub fn summary(&self) -> String {
        if self.cancelled {
            return "Stopped. Nothing more will run for this command.".into();
        }
        let r = self.result();
        for key in ["error", "reason", "blocker", "details"] {
            if let Some(s) = r[key].as_str().filter(|s| !s.trim().is_empty()) {
                return s.trim().to_string();
            }
        }
        if let Some(e) = self.json.as_ref().and_then(|j| j["error"].as_str()) {
            return e.trim().to_string();
        }
        if self.text.trim().is_empty() {
            match self.exit {
                Some(code) => format!("The command ended with code {code} and said nothing."),
                None => "The command ended without an exit code.".into(),
            }
        } else {
            self.text.trim().to_string()
        }
    }
}

/// Shared between the worker threads and the window.
#[derive(Default)]
struct Inner {
    lines: VecDeque<String>,
    /// Every line ever pushed, for numbering the ones still kept.
    total: usize,
    step: Option<String>,
    log: Option<PathBuf>,
    done: Option<Outcome>,
    cancelled: bool,
    /// Bumped on every change, so the window redraws only when something arrived.
    version: u64,
}

impl Inner {
    fn push(&mut self, line: String) {
        let line = line.trim_end().to_string();
        if line.is_empty() {
            return;
        }
        if self.lines.len() == KEEP_LINES {
            self.lines.pop_front();
        }
        self.lines.push_back(line);
        self.total += 1;
        self.version += 1;
    }
}

/// One running (or finished) mmt command.
pub struct Job {
    pub args: Vec<String>,
    pub started: Instant,
    pid: Option<u32>,
    inner: Arc<Mutex<Inner>>,
}

/// What the window shows of a job, copied out under the lock.
pub struct Snapshot {
    pub lines: Vec<String>,
    pub step: Option<String>,
    pub version: u64,
    pub done: Option<Outcome>,
}

impl Job {
    /// Starts `mmt ARGS --json` in `cwd`. `wake` is called whenever new output arrives.
    pub fn start(launcher: &Launcher, args: Vec<String>, cwd: &Path, wake: Option<rune::Waker>) -> Job {
        let inner = Arc::new(Mutex::new(Inner::default()));
        let mut full = args.clone();
        full.push("--json".into());
        let mut command = launcher_command(launcher);
        command
            .args(&full)
            .current_dir(cwd)
            .env("MMT_PROGRESS", "1")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        hide_and_group(&mut command);
        let wake = wake.unwrap_or_else(|| Arc::new(|| {}));
        let child = command.spawn();
        let mut child = match child {
            Ok(child) => child,
            Err(e) => {
                let mut i = inner.lock().unwrap();
                i.done = Some(Outcome {
                    exit: None,
                    json: None,
                    text: format!("Couldn't start {}: {e}", launcher.program.display()),
                    cancelled: false,
                });
                drop(i);
                wake();
                return Job { args, started: Instant::now(), pid: None, inner };
            }
        };
        let pid = child.id();
        let mut stdout = child.stdout.take().expect("piped stdout");
        let stderr = child.stderr.take().expect("piped stderr");

        // stdout carries the one JSON answer at the end.
        let out = std::thread::spawn(move || {
            let mut bytes = Vec::new();
            let _ = stdout.read_to_end(&mut bytes);
            String::from_utf8_lossy(&bytes).into_owned()
        });

        // stderr: progress lines, and anything the launcher says (finding or downloading Java).
        let (err_inner, err_wake) = (inner.clone(), wake.clone());
        let err = std::thread::spawn(move || {
            let mut reader = BufReader::new(stderr);
            let mut said = String::new();
            let mut buf = Vec::new();
            while reader.read_until(b'\n', &mut buf).map(|n| n > 0).unwrap_or(false) {
                let line = String::from_utf8_lossy(&buf).trim_end().to_string();
                buf.clear();
                let mut i = err_inner.lock().unwrap();
                if let Some(json) = line.strip_prefix("mmt-progress ") {
                    if let Ok(v) = serde_json::from_str::<Value>(json) {
                        i.step = v["step"].as_str().map(str::to_string);
                        i.log = v["log"].as_str().map(PathBuf::from);
                        i.version += 1;
                    }
                } else {
                    said.push_str(&line);
                    said.push('\n');
                    i.push(line);
                }
                drop(i);
                err_wake();
            }
            said
        });

        // The step's log file, followed as it grows.
        let (tail_inner, tail_wake) = (inner.clone(), wake.clone());
        let tail = std::thread::spawn(move || tail_logs(tail_inner, tail_wake));

        let (wait_inner, wait_wake) = (inner.clone(), wake);
        std::thread::spawn(move || {
            let status = child.wait();
            let stdout = out.join().unwrap_or_default();
            let said = err.join().unwrap_or_default();
            let json = last_json(&stdout);
            let mut i = wait_inner.lock().unwrap();
            let cancelled = i.cancelled;
            i.done = Some(Outcome {
                exit: status.ok().and_then(|s| s.code()),
                json,
                text: if stdout.trim().is_empty() { said } else { stdout },
                cancelled,
            });
            i.version += 1;
            drop(i);
            let _ = tail.join();
            wait_wake();
        });
        Job { args, started: Instant::now(), pid: Some(pid), inner }
    }

    pub fn snapshot(&self, max_lines: usize) -> Snapshot {
        let i = self.inner.lock().unwrap();
        let skip = i.lines.len().saturating_sub(max_lines);
        Snapshot {
            lines: i.lines.iter().skip(skip).cloned().collect(),
            step: i.step.clone(),
            version: i.version,
            done: i.done.clone(),
        }
    }

    pub fn total_lines(&self) -> usize {
        self.inner.lock().unwrap().total
    }

    /// A job with no process: given output, step and answer. Headless scenes show states with it.
    pub fn fixture(args: Vec<String>, step: Option<&str>, lines: &[&str], done: Option<Outcome>, elapsed: Duration) -> Job {
        let mut inner = Inner { step: step.map(str::to_string), done, ..Inner::default() };
        for l in lines {
            inner.push((*l).to_string());
        }
        let started = Instant::now().checked_sub(elapsed).unwrap_or_else(Instant::now);
        Job { args, started, pid: None, inner: Arc::new(Mutex::new(inner)) }
    }

    pub fn version(&self) -> u64 {
        self.inner.lock().unwrap().version
    }

    pub fn is_done(&self) -> bool {
        self.inner.lock().unwrap().done.is_some()
    }

    pub fn outcome(&self) -> Option<Outcome> {
        self.inner.lock().unwrap().done.clone()
    }

    /// Stops the command and everything it started (Gradle's client, a game, a server).
    pub fn cancel(&self) {
        {
            let mut i = self.inner.lock().unwrap();
            if i.done.is_some() || i.cancelled {
                return;
            }
            i.cancelled = true;
            i.push("Stopping…".into());
        }
        if let Some(pid) = self.pid {
            kill_tree(pid);
        }
    }
}

impl Drop for Job {
    /// Closing the window never leaves a command running in the background.
    fn drop(&mut self) {
        self.cancel();
    }
}

fn launcher_command(launcher: &Launcher) -> Command {
    #[cfg(windows)]
    {
        // Rust starts a .cmd through cmd.exe and quotes each argument for it.
        Command::new(&launcher.program)
    }
    #[cfg(not(windows))]
    {
        let mut c = Command::new("/bin/sh");
        c.arg(&launcher.program);
        c
    }
}

#[cfg(windows)]
fn hide_and_group(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn hide_and_group(command: &mut Command) {
    use std::os::unix::process::CommandExt;
    // Its own process group, so cancelling stops the JVMs it starts too.
    command.process_group(0);
}

#[cfg(windows)]
fn kill_tree(pid: u32) {
    use std::os::windows::process::CommandExt;
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(0x0800_0000)
        .status();
}

#[cfg(not(windows))]
fn kill_tree(pid: u32) {
    let group = format!("-{pid}");
    let _ = Command::new("kill").args(["-TERM", "--", &group]).status();
    std::thread::sleep(Duration::from_millis(1500));
    let _ = Command::new("kill").args(["-KILL", "--", &group]).stderr(Stdio::null()).status();
}

/// The last line that parses as a JSON object: the launcher may print a word about Java first.
fn last_json(stdout: &str) -> Option<Value> {
    if let Ok(v) = serde_json::from_str::<Value>(stdout.trim()) {
        if v.is_object() {
            return Some(v);
        }
    }
    stdout
        .lines()
        .rev()
        .filter(|l| l.trim_start().starts_with('{'))
        .find_map(|l| serde_json::from_str::<Value>(l.trim()).ok().filter(Value::is_object))
}

/// Follows whichever log file the current step writes, a few times a second, off the UI thread.
fn tail_logs(inner: Arc<Mutex<Inner>>, wake: rune::Waker) {
    let mut current: Option<PathBuf> = None;
    let mut offset = 0u64;
    let mut partial = String::new();
    loop {
        let (wanted, finished) = {
            let i = inner.lock().unwrap();
            (i.log.clone(), i.done.is_some())
        };
        if wanted != current {
            // Finish the old file before moving on.
            if let Some(path) = &current {
                read_more(path, &mut offset, &mut partial, &inner, true);
            }
            current = wanted;
            offset = 0;
            partial.clear();
        }
        let grew = match &current {
            Some(path) => read_more(path, &mut offset, &mut partial, &inner, finished),
            None => false,
        };
        if grew {
            wake();
        }
        if finished {
            return;
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

fn read_more(path: &Path, offset: &mut u64, partial: &mut String, inner: &Arc<Mutex<Inner>>, flush: bool) -> bool {
    let Ok(mut file) = std::fs::File::open(path) else { return false };
    let Ok(len) = file.metadata().map(|m| m.len()) else { return false };
    if len < *offset {
        *offset = 0;
    }
    if len == *offset && !(flush && !partial.is_empty()) {
        return false;
    }
    let mut bytes = Vec::new();
    if file.seek(SeekFrom::Start(*offset)).is_err() || file.take(4 << 20).read_to_end(&mut bytes).is_err() {
        return false;
    }
    *offset += bytes.len() as u64;
    partial.push_str(&String::from_utf8_lossy(&bytes));
    let mut lines: Vec<String> = Vec::new();
    while let Some(end) = partial.find('\n') {
        lines.push(partial[..end].trim_end_matches('\r').to_string());
        partial.drain(..=end);
    }
    if flush && !partial.is_empty() {
        lines.push(std::mem::take(partial));
    }
    if lines.is_empty() {
        return false;
    }
    let mut i = inner.lock().unwrap();
    for line in lines {
        i.push(line);
    }
    true
}

/// Opens a file (an HTML report, a screenshot) or a folder with the system's own handler.
pub fn open_path(path: &Path) -> Result<(), String> {
    if rune::tooling::headless_run().is_some() {
        return Ok(());
    }
    #[cfg(windows)]
    let result = {
        use std::os::windows::process::CommandExt;
        Command::new("explorer").arg(path).creation_flags(0x0800_0000).spawn()
    };
    #[cfg(target_os = "macos")]
    let result = Command::new("open").arg(path).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    let result = Command::new("xdg-open").arg(path).spawn();
    result.map(|_| ()).map_err(|e| format!("Couldn't open {}: {e}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_answer_is_the_last_json_line() {
        let out = "Using Java 21\n{\"ok\":true,\"exit\":0}\n";
        assert_eq!(last_json(out).unwrap()["exit"], 0);
        assert!(last_json("no json here").is_none());
    }

    #[test]
    fn a_failed_answer_says_why() {
        let o = Outcome {
            exit: Some(1),
            json: Some(serde_json::json!({"ok": false, "result": {"reason": "No tests ran", "details": "x"}})),
            text: String::new(),
            cancelled: false,
        };
        assert!(!o.ok());
        assert_eq!(o.summary(), "No tests ran");
    }

    #[test]
    fn verbatim_paths_lose_their_prefix() {
        assert_eq!(dunce(Path::new(r"\\?\C:\mods\x")), PathBuf::from(r"C:\mods\x"));
    }
}
