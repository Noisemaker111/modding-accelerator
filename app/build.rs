use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=MMT_EMBED_JAR");
    let out_dir = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR"));
    let dest = out_dir.join("embedded.rs");

    let jar_env = env::var("MMT_EMBED_JAR").unwrap_or_default();
    let jar_env = jar_env.trim();
    if jar_env.is_empty() {
        let code = r#"
#[allow(dead_code)]
pub struct Bundled {
    pub hash: &'static str,
    pub jar: &'static [u8],
    pub launcher_win: &'static [u8],
    pub bootstrap_ps1: &'static [u8],
    pub launcher_unix: &'static [u8],
}

pub const BUNDLED: Option<Bundled> = None;
"#;
        fs::write(&dest, code).expect("write embedded.rs");
        return;
    }

    let manifest_dir = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let repo_root = manifest_dir.parent().expect("repo root above app/");
    let jar_path = fs::canonicalize(jar_env)
        .unwrap_or_else(|e| panic!("MMT_EMBED_JAR={jar_env:?} could not be found: {e}"));
    let win_launcher = fs::canonicalize(repo_root.join("mmt.cmd")).expect("mmt.cmd");
    let bootstrap_ps1 = fs::canonicalize(repo_root.join("tools/bootstrap-jre.ps1")).expect("tools/bootstrap-jre.ps1");
    let unix_launcher = fs::canonicalize(repo_root.join("mmt")).expect("mmt");

    for p in [&jar_path, &win_launcher, &bootstrap_ps1, &unix_launcher] {
        println!("cargo:rerun-if-changed={}", p.display());
    }

    let mut hasher = Sha256::new();
    for p in [&jar_path, &win_launcher, &bootstrap_ps1, &unix_launcher] {
        let bytes = fs::read(p).unwrap_or_else(|e| panic!("read {}: {e}", p.display()));
        hasher.update(&(bytes.len() as u64).to_le_bytes());
        hasher.update(&bytes);
    }
    let hash = hasher.finish_hex();

    let lit = |p: &Path| format!("{:?}", p.to_string_lossy());
    let code = format!(
        r#"
#[allow(dead_code)]
pub struct Bundled {{
    pub hash: &'static str,
    pub jar: &'static [u8],
    pub launcher_win: &'static [u8],
    pub bootstrap_ps1: &'static [u8],
    pub launcher_unix: &'static [u8],
}}

pub const BUNDLED: Option<Bundled> = Some(Bundled {{
    hash: {hash:?},
    jar: include_bytes!({jar}),
    launcher_win: include_bytes!({win}),
    bootstrap_ps1: include_bytes!({ps1}),
    launcher_unix: include_bytes!({unix}),
}});
"#,
        hash = &hash[..16],
        jar = lit(&jar_path),
        win = lit(&win_launcher),
        ps1 = lit(&bootstrap_ps1),
        unix = lit(&unix_launcher),
    );
    fs::write(&dest, code).expect("write embedded.rs");
}

struct Sha256 {
    h: [u32; 8],
    buf: Vec<u8>,
    len: u64,
}

impl Sha256 {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];

    fn new() -> Self {
        Self {
            h: [
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
            ],
            buf: Vec::with_capacity(64),
            len: 0,
        }
    }

    fn update(&mut self, data: &[u8]) {
        self.len += data.len() as u64;
        let mut i = 0;
        if !self.buf.is_empty() {
            let need = (64 - self.buf.len()).min(data.len());
            self.buf.extend_from_slice(&data[..need]);
            i += need;
            if self.buf.len() == 64 {
                let block: [u8; 64] = self.buf.as_slice().try_into().unwrap();
                self.compress(&block);
                self.buf.clear();
            }
        }
        while i + 64 <= data.len() {
            let block: [u8; 64] = data[i..i + 64].try_into().unwrap();
            self.compress(&block);
            i += 64;
        }
        if i < data.len() {
            self.buf.extend_from_slice(&data[i..]);
        }
    }

    fn compress(&mut self, block: &[u8; 64]) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes(block[i * 4..i * 4 + 4].try_into().unwrap());
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = self.h;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = h.wrapping_add(s1).wrapping_add(ch).wrapping_add(Self::K[i]).wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        for (slot, delta) in self.h.iter_mut().zip([a, b, c, d, e, f, g, h]) {
            *slot = slot.wrapping_add(delta);
        }
    }

    fn finish_hex(mut self) -> String {
        let bits = self.len * 8;
        self.update(&[0x80]);
        while self.buf.len() != 56 {
            self.update(&[0]);
        }
        self.update(&bits.to_be_bytes());
        let mut out = String::with_capacity(64);
        for word in self.h {
            out.push_str(&format!("{word:08x}"));
        }
        out
    }
}
