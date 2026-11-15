//! The pages, laid out headless with real saved answers: no CLI runs here.

use rune::{Env, Runtime};
use serde_json::json;

use crate::checks::{self, Check, Node};
use crate::cli::Outcome;
use crate::shell::Verdict;
use crate::{App, Screen, init, view};

fn answer(ok: bool, result: serde_json::Value) -> Outcome {
    Outcome {
        exit: Some(if ok { 0 } else { 1 }),
        json: Some(json!({"ok": ok, "exit": if ok { 0 } else { 1 }, "seconds": 12.5, "result": result})),
        text: String::new(),
        cancelled: false,
    }
}

fn runtime(app: App) -> Runtime<App> {
    let mut rt = Runtime::new(app, view, (1120.0, 760.0), Env { system_dark: true, scale: 1.0 });
    rt.frame(0.0);
    rt
}

fn scene(name: &str) -> App {
    let mut app = init();
    let (_, apply) = crate::scenes::SCENES.iter().find(|(n, _)| *n == name).expect("scene");
    apply(&mut app);
    app
}

#[test]
fn only_a_behaviour_check_counts_as_working() {
    let loaded = answer(true, json!({"tier": "client-render", "behaviorVerified": false}));
    let proven = answer(true, json!({"tier": "client-render", "behaviorVerified": true}));
    let data = answer(true, json!({"tier": "data", "behaviorVerified": false}));
    assert_eq!(Verdict::of(&data), Verdict::Passed, "only a game check can be merely loaded");
    let logic = answer(true, json!({"gameVerified": false}));
    let failed = answer(false, json!({"reason": "No tests ran"}));
    assert_eq!(Verdict::of(&loaded), Verdict::Loaded);
    assert_eq!(Verdict::of(&proven), Verdict::Works);
    assert_eq!(Verdict::of(&logic), Verdict::Passed);
    assert_eq!(Verdict::of(&failed), Verdict::Failed);
    assert_eq!(Verdict::of(&Outcome { cancelled: true, ..failed }), Verdict::Stopped);
}

#[test]
fn a_client_that_only_loaded_leaves_the_behaviour_node_dark() {
    let app = scene("checks-not-proven");
    assert_eq!(checks::node(&app, Check::Client), Node::Done(Verdict::Loaded));
    assert_eq!(checks::node(&app, Check::Behaviour), Node::Idle);
    assert_eq!(checks::standing(&app).0, "loads in game");
    let rt = runtime(app);
    assert!(rt.find("node-behaviour").is_some());
    assert!(rt.find("assert").is_some(), "the behaviour node asks for the mod's own check");
}

#[test]
fn every_scene_lays_out() {
    for (name, _) in crate::scenes::SCENES {
        let rt = runtime(scene(name));
        assert!(rt.find("titlebar").is_some(), "{name} has the titlebar");
    }
}

#[test]
fn checks_wait_for_a_project() {
    let mut app = init();
    app.screen = Screen::Checks;
    let rt = runtime(app);
    assert!(rt.find("run-main").is_none(), "nothing runs before a project is open");
    assert!(rt.find("open-first").is_some());
}

#[test]
fn a_pack_draws_every_mod_and_marks_the_clashes() {
    let rt = runtime(scene("pack-found"));
    assert!(rt.find("mod-sparkle-3.1.jar").is_some());
    assert!(rt.find("mod-lanterns-1.0.jar").is_some());
}
