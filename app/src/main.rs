//! Minecraft Modding Tools: a window over the modaccel command line for people who don't use one.
//! Every action runs the CLI with `--json` (see `cli.rs`); this program only shows what it says.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod checks;
mod cli;
mod home;
mod pack;
mod perf;
mod port;
mod scenes;
mod shell;
#[cfg(test)]
mod tests;
mod theme;

use std::collections::HashMap;
use std::path::PathBuf;
use std::time::Duration;

use rune::{App as RuneApp, AppSpec, Cx, El, Image, ImageCache, TextEdit, Titlebar, Tx, Waker};

use cli::{Job, Launcher, Outcome};

pub const TITLE: &str = "Minecraft Modding Tools";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Screen {
    Project,
    Checks,
    Port,
    Pack,
    Perf,
}

impl Screen {
    fn from_name(name: &str) -> Option<Screen> {
        match name {
            "home" | "project" => Some(Screen::Project),
            "checks" | "test" => Some(Screen::Checks),
            "port" => Some(Screen::Port),
            "pack" | "modpack" => Some(Screen::Pack),
            "perf" | "performance" => Some(Screen::Perf),
            _ => None,
        }
    }
}

/// Why a command was started, so its answer lands in the right place.
#[derive(Clone, Debug, PartialEq)]
pub enum Purpose {
    Doctor,
    Check(checks::Check),
    PortPreview,
    PortApply,
    PortUndo,
    Convert,
    ConvertUndo,
    Pack(pack::PackAction),
    PerfList,
    PerfCapture,
    GameStop,
}

/// The command that is running now, or the last one, with what it was for.
pub struct Running {
    pub title: String,
    pub purpose: Purpose,
    pub job: Job,
    /// Output version and whole seconds last drawn, so `poll` redraws only on news.
    pub seen: (u64, u64),
    /// Its answer reached its page.
    pub landed: bool,
}

pub struct App {
    pub screen: Screen,
    pub launcher: Result<Launcher, String>,
    pub waker: Option<Waker>,
    pub images: Option<ImageCache>,

    pub project: Option<PathBuf>,
    pub project_edit: TextEdit,
    pub doctor: Option<Outcome>,

    pub running: Option<Running>,
    /// Checks still to run after this one ("Run the main checks").
    pub queue: Vec<checks::Check>,
    pub show_output: bool,

    pub checks: checks::Checks,
    pub port: port::PortWizard,
    pub pack: pack::PackState,
    pub perf: perf::PerfState,
    /// Screenshots the game checks took, by path.
    pub captures: HashMap<String, Image>,
    pub notice: Option<String>,
}

fn init() -> App {
    let mut app = App {
        screen: Screen::Project,
        launcher: Launcher::find(),
        waker: None,
        images: None,
        project: None,
        project_edit: TextEdit::new(""),
        doctor: None,
        running: None,
        queue: Vec::new(),
        show_output: true,
        checks: checks::Checks::default(),
        port: port::PortWizard::default(),
        pack: pack::PackState::default(),
        perf: perf::PerfState::default(),
        captures: HashMap::new(),
        notice: None,
    };
    // `--project FOLDER` and `--screen NAME` open the app on a project and a page.
    let args = rune::tooling::extra_args();
    let mut it = args.iter();
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--project" => {
                if let Some(path) = it.next() {
                    let path = cli::dunce(&std::fs::canonicalize(path).unwrap_or_else(|_| PathBuf::from(path)));
                    app.project_edit = TextEdit::new(path.display().to_string());
                    app.project = Some(path);
                }
            }
            "--screen" => {
                if let Some(screen) = it.next().and_then(|n| Screen::from_name(n)) {
                    app.screen = screen;
                }
            }
            _ => {}
        }
    }
    app
}

fn setup(s: &mut App, waker: Waker) {
    s.images = Some(ImageCache::new(waker.clone()));
    s.waker = Some(waker);
    let shots: Vec<String> = s.checks.results.values().filter_map(checks::capture_path).collect();
    for shot in shots {
        s.load_capture(&shot);
    }
    if s.project.is_some() && s.doctor.is_none() && s.running.is_none() {
        s.run_doctor();
    }
}

impl App {
    /// Starts a command unless one is running; the window keeps drawing while it runs.
    pub fn start(&mut self, title: impl Into<String>, purpose: Purpose, args: Vec<String>) {
        if self.busy() {
            self.notice = Some("Something is already running. Wait for it, or press Stop.".into());
            return;
        }
        let launcher = match &self.launcher {
            Ok(l) => l.clone(),
            Err(e) => {
                self.notice = Some(e.clone());
                return;
            }
        };
        let cwd = self.project.clone().filter(|p| p.is_dir()).unwrap_or_else(|| launcher.home.clone());
        self.notice = None;
        let job = Job::start(&launcher, args, &cwd, self.waker.clone());
        self.show_output = true;
        self.running = Some(Running { title: title.into(), purpose, job, seen: (0, 0), landed: false });
    }

    pub fn busy(&self) -> bool {
        self.running.as_ref().is_some_and(|r| !r.landed)
    }

    pub fn running_purpose(&self) -> Option<&Purpose> {
        self.running.as_ref().filter(|r| !r.landed).map(|r| &r.purpose)
    }

    pub fn cancel(&mut self) {
        self.queue.clear();
        if let Some(r) = &self.running {
            r.job.cancel();
        }
    }

    /// `--root FOLDER` for project commands.
    pub fn root_args(&self) -> Option<Vec<String>> {
        self.project.as_ref().map(|p| vec!["--root".into(), p.display().to_string()])
    }

    pub fn set_project(&mut self, path: PathBuf) {
        let path = cli::dunce(&std::fs::canonicalize(&path).unwrap_or(path));
        if !path.is_dir() {
            self.notice = Some(format!("{} isn't a folder.", path.display()));
            return;
        }
        self.project_edit = TextEdit::new(path.display().to_string());
        self.project = Some(path);
        self.doctor = None;
        self.checks = checks::Checks::default();
        self.port = port::PortWizard::default();
        self.run_doctor();
    }

    pub fn run_doctor(&mut self) {
        if let Some(mut args) = self.root_args() {
            args.insert(0, "doctor".into());
            self.start("Looking at your project", Purpose::Doctor, args);
        }
    }

    /// A finished command's answer goes where its page shows it.
    fn finish(&mut self, purpose: Purpose, outcome: Outcome) {
        // A pass folds its output away; a failure keeps it open to read.
        self.show_output = !outcome.ok();
        match purpose {
            Purpose::Doctor => {
                self.port.prefill(&outcome);
                self.doctor = Some(outcome);
            }
            Purpose::Check(check) => {
                let passed = outcome.ok();
                if let Some(path) = checks::capture_path(&outcome) {
                    self.load_capture(&path);
                }
                self.checks.results.insert(check, outcome);
                if !passed {
                    self.queue.clear();
                }
                if let Some(next) = (!self.queue.is_empty()).then(|| self.queue.remove(0)) {
                    checks::run(self, next);
                }
            }
            Purpose::PortPreview => self.port.preview = Some(outcome),
            Purpose::PortApply => {
                self.port.undo = outcome.result()["undo"].as_str().map(str::to_string);
                self.port.applied = Some(outcome);
            }
            Purpose::PortUndo => {
                if outcome.ok() {
                    self.port.undo = None;
                }
                self.port.undone = Some(outcome);
            }
            Purpose::Convert => {
                self.port.convert_undone = None;
                self.port.converted = Some(outcome);
            }
            Purpose::ConvertUndo => self.port.convert_undone = Some(outcome),
            Purpose::Pack(action) => self.pack.finish(action, outcome),
            Purpose::PerfList => self.perf.list(outcome),
            Purpose::PerfCapture => self.perf.captured(outcome),
            Purpose::GameStop => {
                self.notice = Some(if outcome.ok() { "The hidden game is closed.".into() } else { outcome.summary() });
            }
        }
    }

    pub fn load_capture(&mut self, path: &str) {
        if let Some(cache) = &self.images {
            if !self.captures.contains_key(path) {
                self.captures.insert(path.to_string(), cache.file(path, 0.0, 1.0));
            }
        }
    }
}

/// Runs before each frame: lands a finished command, and redraws only when output or the clock moved.
fn poll(s: &mut App, _t: f64, tx: &mut Tx) -> bool {
    let Some(r) = &mut s.running else { return false };
    if r.landed {
        return false;
    }
    if let Some(outcome) = r.job.outcome() {
        r.landed = true;
        let purpose = r.purpose.clone();
        let printed = r.job.total_lines() > 0;
        s.finish(purpose, outcome);
        // Nothing to read: keep the strip folded.
        if !printed {
            s.show_output = false;
        }
        return true;
    }
    tx.wake_after(Duration::from_millis(500));
    let seen = (r.job.version(), r.job.started.elapsed().as_secs());
    if seen == r.seen {
        return false;
    }
    r.seen = seen;
    true
}

fn view(s: &App, cx: &mut Cx<App>) -> El {
    let page = match s.screen {
        Screen::Project => home::view(s),
        Screen::Checks => checks::view(s),
        Screen::Port => port::view(s),
        Screen::Pack => pack::view(s),
        Screen::Perf => perf::view(s),
    };
    shell::frame(s, cx, page)
}

/// Files dropped on the window: a folder is a project (or a pack on the modpack page), a .zip a pack.
pub fn dropped(s: &mut App, files: &[PathBuf]) {
    let Some(path) = files.first().cloned() else { return };
    let zip = path.extension().is_some_and(|e| e.eq_ignore_ascii_case("zip") || e.eq_ignore_ascii_case("mrpack"));
    if zip || s.screen == Screen::Pack {
        pack::chosen(s, path);
        s.screen = Screen::Pack;
    } else if path.is_dir() {
        s.set_project(path);
        s.screen = Screen::Project;
    }
}

fn main() {
    let spec = AppSpec {
        min_size: Some((860.0, 600.0)),
        scenes: scenes::SCENES,
        fonts: theme::FONTS,
        families: theme::FAMILIES,
        setup: Some(setup),
        poll: Some(poll),
        ..AppSpec::new(TITLE, (1120.0, 760.0), init, view)
    };
    RuneApp::new(spec)
        .titlebar(Titlebar::new(shell::TITLEBAR))
        .on_close(|s: &mut App, _tx| {
            s.cancel();
        })
        .run();
}
