//! Named states for headless snapshots (`--scene NAME`), built from real mmt answers saved
//! in `ui-tests/fixtures` (paths made generic). Nothing here runs a command.

use std::path::PathBuf;
use std::time::Duration;

use rune::TextEdit;

use crate::checks::Check;
use crate::cli::{Job, Outcome};
use crate::{App, Purpose, Running, Screen};

fn fixture(name: &str) -> Outcome {
    let text = match name {
        "doctor" => include_str!("../ui-tests/fixtures/doctor.json"),
        "compile" => include_str!("../ui-tests/fixtures/compile.json"),
        "fast" => include_str!("../ui-tests/fixtures/fast.json"),
        "fast-fail" => include_str!("../ui-tests/fixtures/fast-fail.json"),
        "data-check" => include_str!("../ui-tests/fixtures/data-check.json"),
        "mixin-check" => include_str!("../ui-tests/fixtures/mixin-check.json"),
        "ui-check" => include_str!("../ui-tests/fixtures/ui-check.json"),
        "ui-check-stale" => include_str!("../ui-tests/fixtures/ui-check-stale.json"),
        "port-preview" => include_str!("../ui-tests/fixtures/port-preview.json"),
        "port-apply" => include_str!("../ui-tests/fixtures/port-apply.json"),
        "port-fail" => include_str!("../ui-tests/fixtures/port-fail.json"),
        "pack" => include_str!("../ui-tests/fixtures/pack.json"),
        "perf-list" => include_str!("../ui-tests/fixtures/perf-list.json"),
        "perf" => include_str!("../ui-tests/fixtures/perf.json"),
        "convert" => include_str!("../ui-tests/fixtures/convert.json"),
        _ => "{}",
    };
    let json: serde_json::Value = serde_json::from_str(text).unwrap_or_default();
    Outcome { exit: json["exit"].as_i64().map(|e| e as i32), json: Some(json), text: String::new(), cancelled: false }
}

/// The real ui-check answer, its screenshot pointed at a local copy (`.runs/desktop/ui/shot.png`).
fn client() -> Outcome {
    let mut o = fixture("ui-check");
    let shot = std::fs::canonicalize(".runs/desktop/ui/shot.png").map(|p| crate::cli::dunce(&p)).unwrap_or_else(|_| PathBuf::from("shot.png"));
    if let Some(j) = o.json.as_mut() {
        j["result"]["capture"]["path"] = serde_json::Value::String(shot.display().to_string());
    }
    o
}

const GAME_LOG: &[&str] = &[
    "> Configure project :",
    "Fabric Loom: 1.18.4",
    "> Task :compileJava",
    "> Task :processResources",
    "> Task :classes",
    "> Task :compileClientJava",
    "> Task :clientClasses",
    "> Task :generateDLIConfig",
    "> Task :configureLaunch",
    "> Task :downloadAssets",
    "> Task :runClient",
    "[17:28:15] [main/INFO] (FabricLoader/GameProvider) Loading Minecraft 26.3 with Fabric Loader 0.19.5",
    "[17:28:15] [main/INFO] (FabricLoader) Loading 52 mods:",
    "\t- fabric-api 0.163.0+26.3",
    "\t- prismlab 1.0.0",
    "[17:28:21] [Render thread/INFO] (Minecraft) Setting user: Player",
    "[17:28:24] [Render thread/WARN] (Minecraft) Missing sound for event: minecraft:item.goat_horn.play",
    "[17:28:27] [Render thread/INFO] (Minecraft) Reloading ResourceManager: vanilla, fabric, prismlab",
];

fn project(s: &mut App) {
    s.project = Some(PathBuf::from(r"C:\mods\prism-lab"));
    s.project_edit = TextEdit::new(r"C:\mods\prism-lab");
    let doctor = fixture("doctor");
    s.port.prefill(&doctor);
    s.doctor = Some(doctor);
}

fn passed_to_client(s: &mut App) {
    project(s);
    s.checks.results.insert(Check::Build, fixture("compile"));
    s.checks.results.insert(Check::Fast, fixture("fast"));
    s.checks.results.insert(Check::Data, fixture("data-check"));
    s.checks.results.insert(Check::Mixin, fixture("mixin-check"));
}

fn running(s: &mut App, title: &str, purpose: Purpose, step: &str, lines: &[&str], seconds: u64) {
    let job = Job::fixture(Vec::new(), Some(step), lines, None, Duration::from_secs(seconds));
    s.running = Some(Running { title: title.into(), purpose, job, seen: (0, 0), landed: false });
}

fn finished(s: &mut App, title: &str, purpose: Purpose, lines: &[&str], o: Outcome) {
    let job = Job::fixture(Vec::new(), None, lines, Some(o), Duration::from_secs(0));
    s.running = Some(Running { title: title.into(), purpose, job, seen: (0, 0), landed: true });
}

pub const SCENES: &[(&str, fn(&mut App))] = &[
    ("home-empty", |s| s.screen = Screen::Project),
    ("home", |s| {
        passed_to_client(s);
        s.checks.results.insert(Check::Client, client());
    }),
    ("home-failing", |s| {
        project(s);
        s.checks.results.insert(Check::Fast, fixture("fast-fail"));
    }),
    ("checks-idle", |s| {
        project(s);
        s.screen = Screen::Checks;
    }),
    ("checks-running", |s| {
        passed_to_client(s);
        s.screen = Screen::Checks;
        s.checks.open = Some(Check::Client);
        running(s, "In game check", Purpose::Check(Check::Client), "game", GAME_LOG, 14);
    }),
    ("checks-loaded", |s| {
        passed_to_client(s);
        s.checks.results.insert(Check::Client, client());
        s.checks.open = Some(Check::Client);
        s.screen = Screen::Checks;
    }),
    ("checks-not-proven", |s| {
        passed_to_client(s);
        s.checks.results.insert(Check::Client, client());
        s.checks.open = Some(Check::Behaviour);
        s.screen = Screen::Checks;
    }),
    ("checks-failed", |s| {
        project(s);
        s.checks.results.insert(Check::Build, fixture("compile"));
        let failed = fixture("fast-fail");
        s.checks.results.insert(Check::Fast, failed.clone());
        s.checks.open = Some(Check::Fast);
        s.screen = Screen::Checks;
        finished(s, "Logic check", Purpose::Check(Check::Fast), &["> Configure project :", "Fabric Loom: 1.18.4", "FAILURE: Build failed with an exception.", "> Failed to setup Minecraft, net.fabricmc.loom.util.download.DownloadException: Failed to download"], failed);
        s.show_output = true;
    }),
    ("checks-stale-game", |s| {
        passed_to_client(s);
        s.checks.results.insert(Check::Client, fixture("ui-check-stale"));
        s.checks.open = Some(Check::Client);
        s.screen = Screen::Checks;
    }),
    ("port-idle", |s| {
        project(s);
        s.port.to = TextEdit::new("1.21.11");
        s.screen = Screen::Port;
    }),
    ("port-preview", |s| {
        project(s);
        s.port.to = TextEdit::new("1.21.11");
        s.port.preview = Some(fixture("port-preview"));
        s.screen = Screen::Port;
    }),
    ("port-running", |s| {
        project(s);
        s.port.to = TextEdit::new("1.21.11");
        s.port.preview = Some(fixture("port-preview"));
        s.screen = Screen::Port;
        running(s, "Porting", Purpose::PortApply, "port-initial-build", &["WARNING: A restricted method in java.lang.System has been called", "> Configure project :", "Fabric Loom: 1.18.4", "> Task :compileJava"], 31);
    }),
    ("port-applied", |s| {
        project(s);
        s.port.from = TextEdit::new("26.3");
        s.port.to = TextEdit::new("26.2");
        let applied = fixture("port-apply");
        s.port.undo = applied.result()["undo"].as_str().map(str::to_string);
        s.port.applied = Some(applied);
        s.screen = Screen::Port;
    }),
    ("port-failed", |s| {
        project(s);
        s.port.to = TextEdit::new("1.21.11");
        s.port.preview = Some(fixture("port-fail"));
        s.screen = Screen::Port;
    }),
    ("port-converted", |s| {
        project(s);
        s.port.converted = Some(fixture("convert"));
        s.screen = Screen::Port;
    }),
    ("port-jar", |s| {
        crate::port::chosen_jar(s, PathBuf::from(r"C:\mods\appleskin-neoforge-mc1.21.1-3.0.5.jar"));
        s.port.jar_to = TextEdit::new("1.21.2");
        s.port.jar_result = Some(Outcome {
            exit: Some(0),
            json: Some(serde_json::json!({
                "ok": true,
                "exit": 0,
                "seconds": 4.2,
                "result": {
                    "ok": true,
                    "jar": r"C:\mods\appleskin-neoforge-mc1.21.1-3.0.5.jar",
                    "out": r"C:\mods\appleskin-neoforge-mc1.21.2-3.0.5.jar",
                    "from": "1.21.1",
                    "to": "1.21.2",
                    "changed": {"classes": 2, "methods": 14},
                    "unlinked": [],
                    "silent": []
                }
            })),
            text: String::new(),
            cancelled: false,
        });
        s.screen = Screen::Port;
    }),
    ("pack-idle", |s| s.screen = Screen::Pack),
    ("pack-running", |s| {
        crate::pack::chosen(s, PathBuf::from(r"C:\packs\demo-pack"));
        s.screen = Screen::Pack;
        running(s, "Checking the modpack", Purpose::Pack(crate::pack::PackAction::Check), "read", &[], 3);
    }),
    ("pack-found", |s| {
        crate::pack::chosen(s, PathBuf::from(r"C:\packs\demo-pack"));
        s.pack.check = Some(fixture("pack"));
        s.screen = Screen::Pack;
    }),
    ("perf-idle", |s| s.screen = Screen::Perf),
    ("perf-list", |s| {
        s.perf.list(fixture("perf-list"));
        s.screen = Screen::Perf;
    }),
    ("perf-result", |s| {
        s.perf.list(fixture("perf-list"));
        s.perf.report = serde_json::from_str(include_str!("../ui-tests/fixtures/perf-report.json")).ok();
        s.perf.result = Some(fixture("perf"));
        s.screen = Screen::Perf;
    }),
];
