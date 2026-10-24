import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;

/**
 * VersionMap - what Minecraft renamed, moved or changed between two versions, generated from
 * published mappings instead of written by hand.
 *
 * Fabric's intermediary names are stable across versions: the same intermediary name is the same
 * class, method or field in every release. Joining each version's readable names (Mojang's
 * official mappings, or yarn) through intermediary gives every rename and every signature change.
 *
 *   java tools/VersionMap.java <from> <to> [--names mojmap|yarn] [--out map.json] [--cache dir]
 *   java tools/VersionMap.java --map map.json --explain <build log>
 *   java tools/VersionMap.java --map map.json --fix <build log> --repo <dir> [--rules learned.json] [--dry-run]
 *   java tools/VersionMap.java --map map.json --learn <a.diff> [--learn <b.diff>...] --out learned.json
 *
 * Mappings download once into the cache (default ~/.modaccel/mappings). Versions from 26.1 ship
 * unobfuscated and have no intermediary yet; they are reported, not guessed.
 * Exit: 0 ok, 2 usage or I/O error.
 */
public final class VersionMap {
	static Path cache = Path.of(System.getProperty("user.home"), ".modaccel", "mappings");
	static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	public static void main(String[] args) throws Exception {
		List<String> pos = new ArrayList<>();
		String names = "mojmap", out = null, map = null, explain = null, fix = null, repo = ".", rules = null;
		List<String> learn = new ArrayList<>();
		boolean dryRun = false;
		boolean preflight = false;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--names" -> names = args[++i];
				case "--out" -> out = args[++i];
				case "--cache" -> cache = Path.of(args[++i]);
				case "--map" -> map = args[++i];
				case "--explain" -> explain = args[++i];
				case "--fix" -> fix = args[++i];
				case "--repo" -> repo = args[++i];
				case "--dry-run" -> dryRun = true;
				case "--preflight" -> preflight = true;
				case "--rules" -> rules = args[++i];
				case "--learn" -> learn.add(args[++i]);
				default -> pos.add(args[i]);
			}
		}
		if (preflight && map != null && fix == null) {
			Preflight.run(Path.of(map), Path.of(repo).toAbsolutePath().normalize(), dryRun);
			return;
		}
		if (map != null && fix != null) {
			Fix.run(Path.of(map), Path.of(fix), Path.of(repo).toAbsolutePath().normalize(), dryRun, rules == null ? null : Path.of(rules));
			return;
		}
		if (map != null && !learn.isEmpty()) {
			Learn.run(Path.of(map), learn, Path.of(out != null ? out : "learned.json"));
			return;
		}
		if (map != null && explain != null) {
			Explain.run(Path.of(map), Path.of(explain));
			return;
		}
		if (pos.size() != 2 || !(names.equals("mojmap") || names.equals("yarn"))) {
			System.err.println("usage: java tools/VersionMap.java <from> <to> [--names mojmap|yarn] [--out map.json]");
			System.exit(2);
		}
		long t0 = System.nanoTime();
		Table a = Table.load(pos.get(0), names);
		Table b = Table.load(pos.get(1), names);
		Diff d = Diff.of(a, b);
		Path target = out != null ? Path.of(out)
				: cache.resolve("map-" + names + "-" + pos.get(0) + "-to-" + pos.get(1) + ".json");
		Files.createDirectories(target.toAbsolutePath().getParent());
		Files.writeString(target, d.json(pos.get(0), pos.get(1), names), StandardCharsets.UTF_8);
		System.out.printf("map %s -> %s (%s): classes %d renamed or moved, %d removed; methods %d renamed, %d moved, "
				+ "%d signature changes, %d removed; fields %d renamed, %d removed (%.1fs)%n",
				pos.get(0), pos.get(1), names, d.classRenamed.size(), d.classRemoved.size(), d.methodRenamed.size(),
				d.methodMoved.size(), d.methodSignature.size(), d.methodRemoved.size(), d.fieldRenamed.size(),
				d.fieldRemoved.size(), (System.nanoTime() - t0) / 1e9);
		System.out.println("        " + target);
	}

	// ------------------------------------------------------------------ downloads

	static Path fetch(String url, String name) throws IOException, InterruptedException {
		Path p = cache.resolve(name);
		if (Files.isRegularFile(p) && Files.size(p) > 0) { return p; }
		Files.createDirectories(cache);
		HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
		if (r.statusCode() != 200) { throw new IOException("HTTP " + r.statusCode() + " for " + url); }
		Path tmp = Files.createTempFile(cache, "dl", ".part");
		Files.write(tmp, r.body());
		Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
		return p;
	}

	static String fetchText(String url, String name) throws IOException, InterruptedException {
		return Files.readString(fetch(url, name), StandardCharsets.UTF_8);
	}

	static String zipEntry(Path jar, String entry) throws IOException {
		try (ZipFile z = new ZipFile(jar.toFile())) {
			ZipEntry e = z.getEntry(entry);
			if (e == null) { throw new IOException(entry + " not in " + jar); }
			return new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Tiny JSON value lookup, enough for Mojang's and Fabric's metadata. */
	static String jsonString(String json, String key, int from) {
		Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
		return m.find(from) ? m.group(1) : null;
	}

	// ------------------------------------------------------------------ one version's names

	record Member(String owner, String name, String desc, String interDesc) {
	}

	static final class Table {
		final String version;
		final Map<String, String> classes = new HashMap<>();   // intermediary -> named
		final Map<String, Member> methods = new HashMap<>();   // key -> named
		final Map<String, Member> fields = new HashMap<>();
		final Map<String, List<String>> methodsByInterName = new HashMap<>();

		Table(String version) { this.version = version; }

		static String key(String interOwner, String interName, String interDesc) {
			boolean stable = interName.startsWith("method_") || interName.startsWith("field_");
			return stable ? interOwner + "." + interName : interOwner + "." + interName + interDesc;
		}

		static Table load(String version, String names) throws Exception {
			String manifest = fetchText("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", "version_manifest_v2.json");
			int at = manifest.indexOf("\"id\": \"" + version + "\"");
			if (at < 0) { at = manifest.indexOf("\"id\":\"" + version + "\""); }
			if (at < 0) { throw new IOException("Minecraft " + version + " is not in Mojang's version list"); }
			String versionJson = fetchText(jsonString(manifest, "url", at), "version-" + version + ".json");
			Path inter;
			try {
				inter = fetch("https://maven.fabricmc.net/net/fabricmc/intermediary/" + version + "/intermediary-" + version + "-v2.jar",
						"intermediary-" + version + "-v2.jar");
			} catch (IOException e) {
				throw new IOException("Minecraft " + version + " has no published intermediary (versions from 26.1 ship "
						+ "unobfuscated); this map covers obfuscated versions only for now");
			}
			Tiny it = Tiny.parse(zipEntry(inter, "mappings/mappings.tiny")); // official, intermediary
			Table t = new Table(version);
			Map<String, String> obfToInter = it.classes;
			if (names.equals("yarn")) {
				String meta = fetchText("https://meta.fabricmc.net/v2/versions/yarn/" + version, "yarn-meta-" + version + ".json");
				String yv = jsonString(meta, "version", 0);
				if (yv == null) { throw new IOException("no yarn for " + version); }
				Path y = fetch("https://maven.fabricmc.net/net/fabricmc/yarn/" + yv + "/yarn-" + yv + "-v2.jar", "yarn-" + yv + "-v2.jar");
				Tiny yt = Tiny.parse(zipEntry(y, "mappings/mappings.tiny")); // intermediary, named
				Map<String, String> interToNamed = yt.classes;
				for (String obf : obfToInter.keySet()) {
					String in = obfToInter.get(obf);
					t.classes.put(in, interToNamed.getOrDefault(in, in));
				}
				fill(t, it, obfToInter, (owner, name, interDesc, isMethod) -> {
					String named = (isMethod ? yt.methods : yt.fields).get(owner + "." + name + (isMethod ? interDesc : ""));
					if (named == null && !isMethod) { named = yt.fieldsNoDesc.get(owner + "." + name); }
					return named != null ? named : name;
				}, interToNamed);
			} else {
				int dl = versionJson.indexOf("\"client_mappings\"");
				if (dl < 0) { throw new IOException("Minecraft " + version + " publishes no official mappings"); }
				String proguard = fetchText(jsonString(versionJson, "url", dl), "client-" + version + ".txt");
				int sdl = versionJson.indexOf("\"server_mappings\"");
				Proguard pg = Proguard.parse(proguard);
				if (sdl >= 0) { pg.merge(Proguard.parse(fetchText(jsonString(versionJson, "url", sdl), "server-" + version + ".txt"))); }
				Map<String, String> interToNamed = new HashMap<>();
				for (var e : obfToInter.entrySet()) {
					interToNamed.put(e.getValue(), pg.classes.getOrDefault(e.getKey(), e.getKey()));
				}
				t.classes.putAll(interToNamed);
				// Official names keyed by obf owner + obf name + obf descriptor, joined to intermediary.
				Map<String, String> interToObf = new HashMap<>();
				obfToInter.forEach((o, i) -> interToObf.put(i, o));
				fill(t, it, obfToInter, (owner, name, interDesc, isMethod) -> null, interToNamed);
				// Second pass: the readable name for each member comes from the obf side.
				for (var entry : it.memberRows) {
					String named = entry.method
							? pg.methods.get(entry.obfOwner + "." + entry.obfName + entry.obfDesc)
							: pg.fields.get(entry.obfOwner + "." + entry.obfName);
					if (named == null) { continue; }
					String interOwner = obfToInter.getOrDefault(entry.obfOwner, entry.obfOwner);
					String interDesc = Tiny.remapDesc(entry.obfDesc, obfToInter);
					Map<String, Member> table = entry.method ? t.methods : t.fields;
					String k = key(interOwner, entry.interName, interDesc);
					Member m = table.get(k);
					if (m != null) { table.put(k, new Member(m.owner(), named, m.desc(), m.interDesc())); }
				}
			}
			for (var e : t.methods.entrySet()) {
				String k = e.getKey();
				String name = k.substring(k.indexOf('.') + 1);
				if (name.startsWith("method_")) { t.methodsByInterName.computeIfAbsent(name, x -> new ArrayList<>()).add(k); }
			}
			return t;
		}

		interface Namer { String name(String interOwner, String interName, String interDesc, boolean method); }

		static void fill(Table t, Tiny it, Map<String, String> obfToInter, Namer namer, Map<String, String> interToNamed) {
			for (var row : it.memberRows) {
				String interOwner = obfToInter.getOrDefault(row.obfOwner, row.obfOwner);
				String interDesc = Tiny.remapDesc(row.obfDesc, obfToInter);
				String named = namer.name(interOwner, row.interName, interDesc, row.method);
				if (named == null) { named = row.interName.startsWith("method_") || row.interName.startsWith("field_") ? row.obfName : row.interName; }
				String namedDesc = Tiny.remapDesc(interDesc, interToNamed);
				Member m = new Member(interToNamed.getOrDefault(interOwner, interOwner), named, namedDesc, interDesc);
				(row.method ? t.methods : t.fields).put(key(interOwner, row.interName, interDesc), m);
			}
		}
	}

	// ------------------------------------------------------------------ tiny v2

	static final class Tiny {
		record Row(boolean method, String obfOwner, String obfName, String obfDesc, String interName) {
		}

		final Map<String, String> classes = new HashMap<>();      // ns0 class -> ns1 class
		final Map<String, String> methods = new HashMap<>();      // ns0 owner.name+desc -> ns1 name
		final Map<String, String> fields = new HashMap<>();       // ns0 owner.name -> ns1 name
		final Map<String, String> fieldsNoDesc = fields;
		final List<Row> memberRows = new ArrayList<>();

		static Tiny parse(String text) {
			Tiny t = new Tiny();
			String owner0 = null;
			for (String line : text.split("\r?\n")) {
				if (line.startsWith("c\t")) {
					String[] p = line.split("\t", -1);
					owner0 = p[1];
					t.classes.put(p[1], p[2].isEmpty() ? p[1] : p[2]);
				} else if (owner0 != null && (line.startsWith("\tm\t") || line.startsWith("\tf\t"))) {
					String[] p = line.split("\t", -1);
					boolean method = p[1].equals("m");
					String desc = p[2], n0 = p[3], n1 = p.length > 4 && !p[4].isEmpty() ? p[4] : n0;
					if (method) { t.methods.put(owner0 + "." + n0 + desc, n1); } else { t.fields.put(owner0 + "." + n0, n1); }
					t.memberRows.add(new Row(method, owner0, n0, desc, n1));
				}
			}
			return t;
		}

		static final Pattern CLASS_IN_DESC = Pattern.compile("L([^;]+);");

		static String remapDesc(String desc, Map<String, String> classes) {
			Matcher m = CLASS_IN_DESC.matcher(desc);
			StringBuilder sb = new StringBuilder();
			while (m.find()) { m.appendReplacement(sb, Matcher.quoteReplacement("L" + classes.getOrDefault(m.group(1), m.group(1)) + ";")); }
			m.appendTail(sb);
			return sb.toString();
		}
	}

	// ------------------------------------------------------------------ Mojang ProGuard

	static final class Proguard {
		final Map<String, String> classes = new HashMap<>();   // obf -> named (slashes)
		final Map<String, String> methods = new HashMap<>();   // obfOwner.obfName+obfDesc -> named
		final Map<String, String> fields = new HashMap<>();    // obfOwner.obfName -> named

		void merge(Proguard o) {
			o.classes.forEach(classes::putIfAbsent);
			o.methods.forEach(methods::putIfAbsent);
			o.fields.forEach(fields::putIfAbsent);
		}

		static Proguard parse(String text) {
			// Pass 1: classes, so member descriptors can be written in obf names.
			Map<String, String> namedToObf = new HashMap<>();
			Proguard pg = new Proguard();
			for (String line : text.split("\r?\n")) {
				if (line.isEmpty() || line.startsWith("#") || line.startsWith(" ")) { continue; }
				int arrow = line.indexOf(" -> ");
				String named = line.substring(0, arrow).replace('.', '/');
				String obf = line.substring(arrow + 4, line.length() - 1).replace('.', '/');
				pg.classes.put(obf, named);
				namedToObf.put(named, obf);
			}
			String owner = null;
			for (String line : text.split("\r?\n")) {
				if (line.isEmpty() || line.startsWith("#")) { continue; }
				if (!line.startsWith(" ")) {
					owner = line.substring(line.indexOf(" -> ") + 4, line.length() - 1).replace('.', '/');
					continue;
				}
				String s = line.trim();
				if (s.startsWith("#")) { continue; }
				int arrow = s.indexOf(" -> ");
				String left = s.substring(0, arrow), obfName = s.substring(arrow + 4);
				int paren = left.indexOf('(');
				if (paren < 0) {
					String[] tp = left.split(" ");
					pg.fields.put(owner + "." + obfName, tp[tp.length - 1]);
					continue;
				}
				String head = left.replaceFirst("^\\d+:\\d+:", "");
				paren = head.indexOf('(');
				int close = head.indexOf(')');
				String[] rn = head.substring(0, paren).split(" ");
				String ret = rn[0], name = rn[1];
				String params = head.substring(paren + 1, close);
				StringBuilder desc = new StringBuilder("(");
				if (!params.isEmpty()) { for (String p : params.split(",")) { desc.append(type(p, namedToObf)); } }
				desc.append(')').append(type(ret, namedToObf));
				pg.methods.put(owner + "." + obfName + desc, name);
			}
			return pg;
		}

		static String type(String t, Map<String, String> namedToObf) {
			int dims = 0;
			while (t.endsWith("[]")) { dims++; t = t.substring(0, t.length() - 2); }
			String base = switch (t) {
				case "void" -> "V"; case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C";
				case "short" -> "S"; case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
				default -> {
					String slash = t.replace('.', '/');
					yield "L" + namedToObf.getOrDefault(slash, slash) + ";";
				}
			};
			return "[".repeat(dims) + base;
		}
	}

	// ------------------------------------------------------------------ the diff

	record Change(String owner, String from, String to, String desc, String newDesc, String newOwner) {
	}

	static final class Diff {
		final List<String[]> classRenamed = new ArrayList<>();
		final List<String> classRemoved = new ArrayList<>();
		final List<Change> methodRenamed = new ArrayList<>(), methodMoved = new ArrayList<>(),
				methodSignature = new ArrayList<>(), methodRemoved = new ArrayList<>(),
				fieldRenamed = new ArrayList<>(), fieldRemoved = new ArrayList<>();

		static boolean noise(String name) { return name.startsWith("lambda$") || name.contains("$") || name.equals("<clinit>"); }

		static Diff of(Table a, Table b) {
			Diff d = new Diff();
			for (var e : a.classes.entrySet()) {
				String nb = b.classes.get(e.getKey());
				if (nb == null) { d.classRemoved.add(e.getValue()); }
				else if (!nb.equals(e.getValue())) { d.classRenamed.add(new String[] { e.getValue(), nb }); }
			}
			for (var e : a.methods.entrySet()) {
				Member ma = e.getValue();
				if (noise(ma.name())) { continue; }
				Member mb = b.methods.get(e.getKey());
				if (mb == null) {
					String interName = e.getKey().substring(e.getKey().indexOf('.') + 1);
					List<String> elsewhere = b.methodsByInterName.getOrDefault(interName, List.of());
					if (!elsewhere.isEmpty()) {
						Member mv = b.methods.get(elsewhere.get(0));
						d.methodMoved.add(new Change(ma.owner(), ma.name(), mv.name(), ma.desc(), mv.desc(), mv.owner()));
					} else {
						d.methodRemoved.add(new Change(ma.owner(), ma.name(), null, ma.desc(), null, null));
					}
					continue;
				}
				boolean renamed = !mb.name().equals(ma.name());
				boolean sig = !mb.interDesc().equals(ma.interDesc());
				if (renamed) { d.methodRenamed.add(new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner())); }
				if (sig) { d.methodSignature.add(new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner())); }
			}
			for (var e : a.fields.entrySet()) {
				Member fa = e.getValue();
				if (noise(fa.name())) { continue; }
				Member fb = b.fields.get(e.getKey());
				if (fb == null) { d.fieldRemoved.add(new Change(fa.owner(), fa.name(), null, fa.desc(), null, null)); }
				else if (!fb.name().equals(fa.name())) { d.fieldRenamed.add(new Change(fa.owner(), fa.name(), fb.name(), fa.desc(), fb.desc(), fb.owner())); }
			}
			Comparator<Change> byOwner = Comparator.comparing(Change::owner).thenComparing(Change::from);
			for (var l : List.of(d.methodRenamed, d.methodMoved, d.methodSignature, d.methodRemoved, d.fieldRenamed, d.fieldRemoved)) { l.sort(byOwner); }
			d.classRenamed.sort(Comparator.comparing(x -> x[0]));
			Collections.sort(d.classRemoved);
			return d;
		}

		static String q(String s) {
			if (s == null) { return "null"; }
			return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}

		static String dots(String s) { return s == null ? null : s.replace('/', '.'); }

		String json(String from, String to, String names) {
			StringBuilder sb = new StringBuilder("{\n");
			sb.append("  \"from\": ").append(q(from)).append(", \"to\": ").append(q(to)).append(", \"names\": ").append(q(names)).append(",\n");
			sb.append("  \"classes\": [");
			for (int i = 0; i < classRenamed.size(); i++) {
				sb.append(i == 0 ? "\n" : ",\n").append("    { \"from\": ").append(q(dots(classRenamed.get(i)[0]))).append(", \"to\": ").append(q(dots(classRenamed.get(i)[1]))).append(" }");
			}
			sb.append("\n  ],\n  \"classesRemoved\": [");
			for (int i = 0; i < classRemoved.size(); i++) { sb.append(i == 0 ? "\n    " : ",\n    ").append(q(dots(classRemoved.get(i)))); }
			sb.append("\n  ],\n");
			list(sb, "methods", methodRenamed, true);
			list(sb, "methodsMoved", methodMoved, true);
			list(sb, "signatures", methodSignature, true);
			list(sb, "methodsRemoved", methodRemoved, false);
			list(sb, "fields", fieldRenamed, true);
			list(sb, "fieldsRemoved", fieldRemoved, false);
			sb.setLength(sb.length() - 2);
			return sb.append("\n}\n").toString();
		}

		static void list(StringBuilder sb, String name, List<Change> l, boolean full) {
			sb.append("  \"").append(name).append("\": [");
			for (int i = 0; i < l.size(); i++) {
				Change c = l.get(i);
				sb.append(i == 0 ? "\n" : ",\n").append("    { \"owner\": ").append(q(dots(c.owner()))).append(", \"from\": ").append(q(c.from())).append(", \"desc\": ").append(q(c.desc()));
				if (full) {
					sb.append(", \"to\": ").append(q(c.to())).append(", \"newDesc\": ").append(q(c.newDesc()));
					if (!c.newOwner().equals(c.owner())) { sb.append(", \"newOwner\": ").append(q(dots(c.newOwner()))); }
				}
				sb.append(" }");
			}
			sb.append("\n  ],\n");
		}
	}

	// ------------------------------------------------------------------ explain a build log

	/** One javac error with the lines javac prints under it. */
	record Err(String file, int line, String message, String source, int caret, String symbol, String location) {
	}

	static List<Err> readErrors(Path log) throws IOException {
		List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
		// Gradle indents javac's lines in multi-project builds.
		Pattern head = Pattern.compile("^\\s*(\\S.*?\\.java):(\\d+): error: (.*)$");
		Map<String, Err> seen = new LinkedHashMap<>();
		for (int i = 0; i < lines.size(); i++) {
			Matcher m = head.matcher(lines.get(i));
			if (!m.find()) { continue; }
			String source = i + 1 < lines.size() ? lines.get(i + 1) : "";
			int caret = i + 2 < lines.size() ? lines.get(i + 2).indexOf('^') : -1;
			int indent = m.start(1);
			if (indent > 0 && source.startsWith(" ".repeat(indent))) { source = source.substring(indent); }
			if (caret >= indent) { caret -= indent; }
			String symbol = null, location = null;
			for (int j = i + 1; j < Math.min(lines.size(), i + 8); j++) {
				String s = lines.get(j).trim();
				if (head.matcher(lines.get(j)).find()) { break; }
				if (s.startsWith("symbol:")) { symbol = s.substring(7).trim(); }
				if (s.startsWith("location:")) { location = s.substring(9).trim(); }
			}
			Err e = new Err(m.group(1), Integer.parseInt(m.group(2)), m.group(3), source, caret, symbol, location);
			seen.putIfAbsent(e.file() + ":" + e.line() + ":" + e.caret() + ":" + e.message() + ":" + symbol, e);
		}
		return new ArrayList<>(seen.values());
	}

	static String simple(String dotted) {
		String s = dotted.substring(dotted.lastIndexOf('.') + 1);
		return s.substring(s.lastIndexOf('$') + 1);
	}

	static String field(String obj, String key) {
		Matcher m = Pattern.compile("\"" + key + "\": \"([^\"]*)\"").matcher(obj);
		return m.find() ? m.group(1) : null;
	}

	/** A generated map, indexed the way compiler errors ask about it. */
	static final class Known {
		String fromVersion, toVersion, names;
		final Map<String, String> classFqn = new LinkedHashMap<>();             // old fqn ($ nested) -> new fqn
		final Map<String, Set<String>> classBySimple = new HashMap<>(), removedClassBySimple = new HashMap<>();
		final Map<String, Set<String>> packages = new HashMap<>();
		final Map<String, Set<String[]>> methodTo = new HashMap<>(), fieldTo = new HashMap<>(); // old name -> {owner simple, new name}
		final Map<String, Set<String>> sigTo = new HashMap<>();
		final Map<String, Set<String>> sigTransitions = new HashMap<>();
		final Set<String> methodGone = new HashSet<>(), fieldGone = new HashSet<>(), sigNames = new HashSet<>();

		static Known load(Path mapFile) throws IOException {
			Known k = new Known();
			String json = Files.readString(mapFile, StandardCharsets.UTF_8);
			k.fromVersion = field(json, "from"); k.toVersion = field(json, "to"); k.names = field(json, "names");
			String section = null;
			Pattern sec = Pattern.compile("^  \"(\\w+)\": \\[");
			for (String line : Files.readString(mapFile, StandardCharsets.UTF_8).split("\n")) {
				Matcher sm = sec.matcher(line);
				if (sm.find()) { section = sm.group(1); continue; }
				if (section == null) { continue; }
				String t = line.trim();
				if (section.equals("classesRemoved")) {
					if (t.startsWith("\"")) {
						String c = t.replaceAll("[\",]", "");
						k.removedClassBySimple.computeIfAbsent(simple(c), x -> new HashSet<>()).add(c);
					}
					continue;
				}
				if (!t.startsWith("{")) { continue; }
				String from = field(t, "from"), to = field(t, "to"), owner = field(t, "owner"), newOwner = field(t, "newOwner");
				String ownerSimple = owner == null ? "" : simple(owner);
				switch (section) {
					case "classes" -> {
						k.classFqn.put(from, to);
						k.classBySimple.computeIfAbsent(simple(from), x -> new HashSet<>()).add(to);
						String pkg = from.contains(".") ? from.substring(0, from.lastIndexOf('.')) : "";
						k.packages.computeIfAbsent(pkg, x -> new HashSet<>()).add(to);
					}
					case "methods", "methodsMoved" -> {
						if (!from.equals(to)) { k.methodTo.computeIfAbsent(from, x -> new HashSet<>()).add(new String[] { ownerSimple, to, newOwner == null ? ownerSimple : simple(newOwner) }); }
					}
					case "signatures" -> {
						k.sigTo.computeIfAbsent(ownerSimple + "." + from, x -> new HashSet<>()).add(to + field(t, "newDesc"));
						k.sigTransitions.computeIfAbsent(ownerSimple + "." + from, x -> new HashSet<>())
								.add(field(t, "desc") + " -> " + field(t, "newDesc"));
						k.sigNames.add(from);
					}
					case "methodsRemoved" -> k.methodGone.add(from);
					case "fields" -> k.fieldTo.computeIfAbsent(from, x -> new HashSet<>()).add(new String[] { ownerSimple, to, ownerSimple });
					case "fieldsRemoved" -> k.fieldGone.add(from);
					default -> { }
				}
			}
			return k;
		}

		/** The one new name for an old member name, narrowed by the type javac says it looked in. */
		static String pick(Set<String[]> candidates, String location) {
			if (candidates == null || candidates.isEmpty()) { return null; }
			String type = locationType(location);
			if (type == null) { return null; }
			Set<String> names = new HashSet<>();
			for (String[] c : candidates) { if (c[0].equals(type) || c[2].equals(type)) { names.add(c[1]); } }
			return names.size() == 1 ? names.iterator().next() : null;
		}

		static String locationType(String location) {
			if (location == null) { return null; }
			Matcher m = Pattern.compile("of type ([\\w.$]+)").matcher(location);
			String t = m.find() ? m.group(1) : location.replaceFirst("^(class|interface|enum|record) ", "");
			t = t.replaceAll("<.*", "").trim();
			return simple(t);
		}
	}

	static final Pattern CALL = Pattern.compile("\\.(\\w+)\\(");

	/** First method the source line calls whose signature the map says changed. */
	static String lineCalls(String source, Set<String> names) {
		Matcher m = CALL.matcher(source);
		while (m.find()) { if (names.contains(m.group(1))) { return m.group(1); } }
		return null;
	}

	// ------------------------------------------------------------------ explain a build log

	static final class Explain {
		static String verdict(Err e, Known k, StringBuilder detail) {
			String sym = e.symbol() == null ? "" : e.symbol();
			String msg = e.message();
			if (msg.startsWith("package ") && msg.endsWith(" does not exist")) {
				return k.packages.containsKey(msg.substring(8, msg.length() - 15)) ? "mechanical: package moved" : "removed: package";
			}
			if (msg.equals("cannot find symbol") && sym.startsWith("class ")) {
				String c = sym.substring(6).trim();
				detail.append(c);
				if (k.classBySimple.containsKey(c)) { detail.append(" -> ").append(k.classBySimple.get(c)); return "mechanical: class renamed/moved"; }
				return k.removedClassBySimple.containsKey(c) ? "removed: class" : "unknown symbol: class";
			}
			if (msg.equals("cannot find symbol") && sym.startsWith("method ")) {
				String n = sym.substring(7, sym.indexOf('('));
				detail.append(n);
				String to = Known.pick(k.methodTo.get(n), e.location());
				if (to != null) { detail.append(" -> ").append(to); return "mechanical: method renamed"; }
				if (k.methodTo.containsKey(n)) { return "renamed, target ambiguous"; }
				return k.methodGone.contains(n) ? "removed: method" : "unknown symbol: method";
			}
			if (msg.equals("cannot find symbol") && sym.startsWith("variable ")) {
				String n = sym.substring(9).trim();
				detail.append(n);
				String to = Known.pick(k.fieldTo.get(n), e.location());
				if (to != null) { detail.append(" -> ").append(to); return "mechanical: field renamed"; }
				return k.fieldGone.contains(n) ? "removed: field" : "unknown symbol: variable";
			}
			Matcher am = Pattern.compile("method (\\w+) in (?:class|interface) (\\w+)").matcher(msg);
			if (am.find() && k.sigTo.containsKey(am.group(2) + "." + am.group(1))) {
				detail.append(am.group(2)).append('.').append(am.group(1)).append(" -> ").append(k.sigTo.get(am.group(2) + "." + am.group(1)));
				return "signature changed (map knows the new one)";
			}
			if (!msg.startsWith("method does not override") && lineCalls(e.source(), k.sigNames) != null) {
				detail.append("line calls ").append(lineCalls(e.source(), k.sigNames));
				return "signature changed (map knows the new one)";
			}
			if (msg.startsWith("method does not override")) { return "override broke"; }
			String m = msg.replaceAll("[A-Z][\\w.$<>,? ]*", "T").replaceAll("\\s+", " ");
			return "other: " + (m.length() > 50 ? m.substring(0, 50) : m);
		}

		static void run(Path mapFile, Path log) throws IOException {
			Known k = Known.load(mapFile);
			Map<String, Integer> counts = new TreeMap<>();
			List<String> examples = new ArrayList<>();
			for (Err e : readErrors(log)) {
				StringBuilder detail = new StringBuilder();
				String v = verdict(e, k, detail);
				counts.merge(v, 1, Integer::sum);
				if (detail.length() > 0 && examples.size() < 25) { examples.add(v + "  " + detail); }
			}
			int total = counts.values().stream().mapToInt(Integer::intValue).sum();
			int mech = counts.entrySet().stream().filter(x -> x.getKey().startsWith("mechanical")).mapToInt(Map.Entry::getValue).sum();
			int sig = counts.getOrDefault("signature changed (map knows the new one)", 0);
			System.out.printf("%d errors: %d mechanical (rename/move), %d signature known, %d other%n", total, mech, sig, total - mech - sig);
			counts.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
					.forEach(x -> System.out.printf("  %4d  %s%n", x.getValue(), x.getKey()));
			for (String ex : examples) { System.out.println("        " + ex); }
		}
	}

	// ------------------------------------------------------------------ apply the mechanical part

	/**
	 * Rewrites what the map makes certain: moved or renamed classes everywhere they are imported
	 * (javac stops at 100 errors, so waiting for each one costs rounds), and renamed methods and
	 * fields exactly where javac reported them. Prints "map-fix: N replacement(s)".
	 */
	static final class Fix {
		static final Set<String> EXCLUDED = Set.of("build", ".gradle", ".git", "run", ".modaccel", ".tools", ".runs", ".lab", "node_modules", "out");
		static Path sourcePath(Err error, Path repo) {
			Path path = Path.of(error.file());
			if (!path.isAbsolute()) { path = repo.resolve(path); }
			path = path.toAbsolutePath().normalize();
			if (!path.startsWith(repo.toAbsolutePath().normalize())) { return null; }
			for (Path part : repo.toAbsolutePath().normalize().relativize(path)) if (EXCLUDED.contains(part.toString())) { return null; }
			try { return path.toRealPath().startsWith(repo.toRealPath()) ? path : null; }
			catch (IOException missing) { return null; }
		}
		static List<Path> sourceFiles(Path repo) throws IOException {
			List<Path> result = new ArrayList<>();
			Files.walkFileTree(repo, new SimpleFileVisitor<Path>() {
				@Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
					return !dir.equals(repo) && (EXCLUDED.contains(dir.getFileName().toString()) || Files.exists(dir.resolve(".git")))
							? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
					if (attrs.isRegularFile() && file.toString().endsWith(".java")) { result.add(file); }
					return FileVisitResult.CONTINUE;
				}
			}); return result;
		}

		static void run(Path mapFile, Path log, Path repo, boolean dryRun, Path rulesFile) throws IOException {
			Known k = Known.load(mapFile);
			Map<Path, String> texts = new LinkedHashMap<>();
			int replacements = 0;
			Map<String, Integer> byKind = new TreeMap<>();

			// 1. Imports of moved/renamed classes, every Java file in the repo.
			List<Path> files = sourceFiles(repo);
			Pattern imp = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);
			Map<String, String> dotted = new HashMap<>();
			for (var e : k.classFqn.entrySet()) { dotted.put(e.getKey().replace('$', '.'), e.getValue().replace('$', '.')); }
			for (Path f : files) {
				String text = Files.readString(f, StandardCharsets.UTF_8);
				Matcher m = imp.matcher(text);
				StringBuilder sb = new StringBuilder();
				List<String[]> simpleRenames = new ArrayList<>();
				int hits = 0;
				while (m.find()) {
					String fqn = m.group(2);
					String to = dotted.get(fqn);
					if (to == null && m.group(1) != null && fqn.contains(".")) {
						String owner = fqn.substring(0, fqn.lastIndexOf('.'));
						if (dotted.containsKey(owner)) { to = dotted.get(owner) + fqn.substring(owner.length()); }
					}
					if (to == null) { continue; }
					m.appendReplacement(sb, Matcher.quoteReplacement("import " + (m.group(1) == null ? "" : "static ") + to + ";"));
					hits++;
					if (m.group(1) == null && !simple(fqn).equals(simple(to))) { simpleRenames.add(new String[] { simple(fqn), simple(to) }); }
				}
				if (hits == 0) { continue; }
				m.appendTail(sb);
				String updated = sb.toString();
				for (String[] r : simpleRenames) {
					int at = updated.indexOf("\n", updated.lastIndexOf("\nimport "));
					String head = updated.substring(0, at), body = updated.substring(at);
					Matcher wm = Pattern.compile("\\b" + Pattern.quote(r[0]) + "\\b").matcher(body);
					int n = 0;
					StringBuilder bb = new StringBuilder();
					while (wm.find()) { wm.appendReplacement(bb, Matcher.quoteReplacement(r[1])); n++; }
					wm.appendTail(bb);
					updated = head + bb;
					hits += n;
				}
				texts.put(f, updated);
				replacements += hits;
				byKind.merge("class", hits, Integer::sum);
			}

			// 2. Renamed methods and fields at the reported line and column.
			for (Err e : readErrors(log)) {
				String sym = e.symbol() == null ? "" : e.symbol();
				if (!e.message().equals("cannot find symbol")) { continue; }
				String from, to;
				boolean method = sym.startsWith("method ");
				if (method) { from = sym.substring(7, sym.indexOf('(')); to = Known.pick(k.methodTo.get(from), e.location()); }
				else if (sym.startsWith("variable ")) { from = sym.substring(9).trim(); to = Known.pick(k.fieldTo.get(from), e.location()); }
				else { continue; }
				if (to == null) { continue; }
				Path f = sourcePath(e, repo);
				if (f == null || !Files.isRegularFile(f)) { continue; }
				String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
				String nl = text.contains("\r\n") ? "\r\n" : "\n";
				String[] lines = text.split("\r?\n", -1);
				if (e.line() < 1 || e.line() > lines.length) { continue; }
				String line = lines[e.line() - 1];
				int col = e.caret() >= 0 && e.caret() < line.length() && line.startsWith(from, e.caret()) ? e.caret() : -1;
				if (col < 0) {
					Matcher wm = Pattern.compile("\\b" + Pattern.quote(from) + "\\b" + (method ? "\\s*\\(" : "")).matcher(line);
					if (!wm.find()) { continue; }
					col = wm.start();
				}
				lines[e.line() - 1] = line.substring(0, col) + to + line.substring(col + from.length());
				texts.put(f, String.join(nl, lines));
				replacements++;
				byKind.merge(method ? "method" : "field", 1, Integer::sum);
			}
			// 3. Learned rewrites (from other mods' real ports of this jump), on the reported lines only.
			if (rulesFile != null && Files.isRegularFile(rulesFile)) {
				Map<Path, Set<String>> pendingImports = new LinkedHashMap<>();
				List<String[]> learned = new ArrayList<>(); // from, to, imports
				Pattern importsField = Pattern.compile("\"imports\": \\[([^\\]]*)\\]");
				for (String line : Files.readAllLines(rulesFile, StandardCharsets.UTF_8)) {
					Matcher fm = Pattern.compile("\"from\": \"((?:[^\"\\\\]|\\\\.)*)\", \"to\": \"((?:[^\"\\\\]|\\\\.)*)\"").matcher(line);
					if (!fm.find()) { continue; }
					Matcher im = importsField.matcher(line);
					learned.add(new String[] { unq(fm.group(1)), unq(fm.group(2)), im.find() ? im.group(1).replace("\"", "") : "" });
				}
				Set<String> done = new HashSet<>();
				for (Err e : readErrors(log)) {
					Path f = sourcePath(e, repo);
					if (f == null || !Files.isRegularFile(f) || !done.add(f + ":" + e.line())) { continue; }
					String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
					String nl = text.contains("\r\n") ? "\r\n" : "\n";
					List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
					if (e.line() < 1 || e.line() > lines.size()) { continue; }
					String line = lines.get(e.line() - 1), before = line;
					Set<String> imports = new LinkedHashSet<>();
					// Whole names only, one rewrite per spot, and for "cannot find symbol" only rules about that symbol.
					String sym = e.symbol() == null ? "" : e.symbol().replaceFirst("^(method|variable|class) ", "").replaceAll("\\(.*", "").trim();
					List<String> usedFrom = new ArrayList<>();
					for (String[] r : learned) {
						if (r[0].isEmpty() || usedFrom.stream().anyMatch(u -> u.contains(r[0]) || r[0].contains(u))) { continue; }
						if (!sym.isEmpty() && e.message().equals("cannot find symbol") && !Learn.tokens(r[0]).contains(sym)) { continue; }
						String pat = (Character.isLetterOrDigit(r[0].charAt(0)) ? "\\b" : "") + Pattern.quote(r[0])
								+ (Character.isLetterOrDigit(r[0].charAt(r[0].length() - 1)) ? "\\b" : "");
						Matcher rm = Pattern.compile(pat).matcher(line);
						if (!rm.find()) { continue; }
						line = rm.replaceAll(Matcher.quoteReplacement(r[1]));
						usedFrom.add(r[0]);
						for (String im : r[2].split(",")) { if (!im.isBlank()) { imports.add(im.trim()); } }
					}
					if (line.equals(before)) { continue; }
					lines.set(e.line() - 1, line);
					pendingImports.computeIfAbsent(f, x -> new LinkedHashSet<>()).addAll(imports);
					texts.put(f, String.join(nl, lines));
					replacements++;
					byKind.merge("learned", 1, Integer::sum);
				}
				// Preserve javac's source line numbers until every reported line has been repaired.
				for (var entry : pendingImports.entrySet()) {
					String text = texts.get(entry.getKey());
					String nl = text.contains("\r\n") ? "\r\n" : "\n";
					List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r?\\n", -1)));
					int after = -1;
					for (int i = 0; i < lines.size(); i++) {
						if (lines.get(i).startsWith("import ") || lines.get(i).startsWith("package ")) { after = i; }
					}
					for (String im : entry.getValue()) {
						if (!lines.contains("import " + im + ";")) { lines.add(++after, "import " + im + ";"); }
					}
					texts.put(entry.getKey(), String.join(nl, lines));
				}
			}
			// API changes need the enclosing Java expression and local declarations, not a file-wide rename.
			Map<Path, List<Err>> apiErrors = new LinkedHashMap<>();
			for (Err e : readErrors(log)) {
				Path f = sourcePath(e, repo);
				if (f != null && Files.isRegularFile(f)) { apiErrors.computeIfAbsent(f, x -> new ArrayList<>()).add(e); }
			}
			for (var entry : apiErrors.entrySet()) {
				Path f = entry.getKey();
				String before = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
				String after = ApiFix.apply(k, f, before, entry.getValue());
				if (!after.equals(before)) { texts.put(f, after); replacements++; byKind.merge("api", 1, Integer::sum); }
			}
			if (!dryRun) { for (var e : texts.entrySet()) { Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8); } }
			System.out.println("map-fix: " + replacements + " replacement(s) in " + texts.size() + " file(s) " + byKind + (dryRun ? " (dry run)" : ""));
		}
	}

	/** Only reviewed AST recipes with a matching target mapping, before the first compile. */
	static final class Preflight {
		static int run(Path mapFile, Path repo, boolean dryRun) throws IOException {
			Known known = Known.load(mapFile);
			if (!"yarn".equals(known.names)) { System.out.println("map-preflight: no reviewed recipes for " + known.names); return 0; }
			int files = 0;
			for (Path file : Fix.sourceFiles(repo)) {
				String before = Files.readString(file, StandardCharsets.UTF_8);
				String after = ApiFix.preflight(known, file, before);
				if (after.equals(before)) { continue; }
				files++;
				if (!dryRun) { Files.writeString(file, after, StandardCharsets.UTF_8); }
			}
			System.out.println("map-preflight: " + files + " file(s)" + (dryRun ? " (dry run)" : ""));
			return files;
		}
	}

	/** Reviewed API migrations. Parse only: no target classes are loaded and no mod code is executed. */
	static final class ApiFix {
		record Edit(int start, int end, String text) { }
		final String source;
		final CompilationUnitTree unit;
		final SourcePositions positions;
		final List<TreePath> paths = new ArrayList<>();
		final List<Edit> edits = new ArrayList<>();

		ApiFix(Path file, String source) throws IOException {
			this.source = source;
			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
			JavaFileObject input = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
				@Override public CharSequence getCharContent(boolean ignored) { return source; }
			};
			JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> { }, List.of("-proc:none"), null, List.of(input));
			unit = task.parse().iterator().next(); positions = Trees.instance(task).getSourcePositions();
			new TreePathScanner<Void, Void>() {
				@Override public Void scan(Tree tree, Void unused) {
					if (tree != null && getCurrentPath() != null) { paths.add(new TreePath(getCurrentPath(), tree)); }
					return super.scan(tree, unused);
				}
			}.scan(unit, null);
		}
		int start(Tree t) { return (int) positions.getStartPosition(unit, t); }
		int end(Tree t) { return (int) positions.getEndPosition(unit, t); }
		String text(Tree t) { return source.substring(start(t), end(t)); }
		void edit(Tree t, String replacement) { edits.add(new Edit(start(t), end(t), replacement)); }
		boolean imported(String type) {
			return unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.getQualifiedIdentifier().toString().equals(type));
		}
		boolean shadowed(String simpleName) {
			return paths.stream().map(TreePath::getLeaf).anyMatch(t ->
					t instanceof ClassTree c && c.getSimpleName().contentEquals(simpleName)
					|| t instanceof VariableTree v && v.getName().contentEquals(simpleName));
		}
		TreePath enclosing(TreePath path, Class<?> kind) {
			for (; path != null; path = path.getParentPath()) { if (kind.isInstance(path.getLeaf())) { return path; } }
			return null;
		}
		List<TreePath> inside(Tree tree) {
			return paths.stream().filter(p -> start(p.getLeaf()) >= start(tree) && end(p.getLeaf()) <= end(tree)).toList();
		}
		List<VariableTree> variables(Tree method, String name) {
			return inside(method).stream().map(TreePath::getLeaf).filter(VariableTree.class::isInstance).map(VariableTree.class::cast)
					.filter(v -> v.getName().contentEquals(name)).toList();
		}
		boolean drawContext(MethodTree method) {
			return imported("net.minecraft.client.gui.DrawContext") && !shadowed("DrawContext") && method.getParameters().stream()
					.anyMatch(p -> p.getType().toString().equals("DrawContext"));
		}
		boolean entityType(String type, ExpressionTree receiver, MethodTree method) {
			Map<String, String> owners = Map.of("Entity", "net.minecraft.entity.Entity", "LivingEntity", "net.minecraft.entity.LivingEntity",
					"PlayerEntity", "net.minecraft.entity.player.PlayerEntity", "ClientPlayerEntity", "net.minecraft.client.network.ClientPlayerEntity",
					"ServerPlayerEntity", "net.minecraft.server.network.ServerPlayerEntity");
			if (type == null || !owners.containsKey(type)) { return false; }
			List<String> importedTypes = unit.getImports().stream().filter(i -> !i.isStatic())
					.map(i -> i.getQualifiedIdentifier().toString()).filter(i -> i.endsWith("." + type)).toList();
			if (!importedTypes.isEmpty()) { return importedTypes.size() == 1 && importedTypes.getFirst().equals(owners.get(type)); }
			// javac can report the type of MinecraftClient.player without importing ClientPlayerEntity.
			if (type.equals("ClientPlayerEntity") && receiver instanceof MemberSelectTree player && player.getIdentifier().contentEquals("player")
					&& imported("net.minecraft.client.MinecraftClient")) {
				List<VariableTree> clients = variables(method, player.getExpression().toString());
				return clients.size() == 1 && clients.getFirst().getType().toString().equals("MinecraftClient");
			}
			return false;
		}
		static String preflight(Known k, Path file, String source) throws IOException {
			ApiFix a = new ApiFix(file, source);
			for (TreePath p : a.paths) {
				if (!(p.getLeaf() instanceof MethodInvocationTree call)
						|| !(call.getMethodSelect() instanceof MemberSelectTree member)) { continue; }
				TreePath enclosing = a.enclosing(p, MethodTree.class);
				if (enclosing == null) { continue; }
				MethodTree method = (MethodTree) enclosing.getLeaf();
				String name = member.getIdentifier().toString();
				boolean context = a.drawContext(method) && method.getParameters().stream()
						.anyMatch(v -> v.getType().toString().equals("DrawContext") && v.getName().contentEquals(member.getExpression().toString()));
				if ("yarn".equals(k.names) && "1.21.5".equals(k.fromVersion) && "1.21.6".equals(k.toVersion)
						&& context && (name.equals("drawText") && call.getArguments().size() == 6
								|| name.equals("drawTextWithShadow") && call.getArguments().size() == 5)
						&& k.sigTransitions.getOrDefault("DrawContext." + name, Set.of()).stream().anyMatch(transition -> {
							String[] pair = transition.split(" -> ", 2);
							return pair.length == 2 && pair[0].startsWith("(Lnet/minecraft/client/font/TextRenderer;")
									&& pair[0].endsWith(")I") && pair[1].equals(pair[0].substring(0, pair[0].length() - 1) + "V");
						})) {
					String opaque = a.opaqueTextColor(call.getArguments().get(4));
					if (opaque != null) a.edit(call.getArguments().get(4), opaque);
				}
				if ("1.21.5".equals(k.toVersion) && context && a.imported("net.minecraft.client.gl.RenderPipelines") && !a.shadowed("RenderPipelines")
						&& (name.equals("drawGuiTexture") || name.equals("drawTexture")) && !call.getArguments().isEmpty()
						&& call.getArguments().getFirst().toString().equals("RenderPipelines.GUI_TEXTURED")
						&& k.sigTo.getOrDefault("DrawContext." + name, Set.of()).stream().anyMatch(s -> s.contains("Ljava/util/function/Function;"))) {
					a.edit(call.getArguments().getFirst(), "net.minecraft.client.render.RenderLayer::getGuiTextured");
				}
				if ("1.21.5".equals(k.toVersion) && context && name.equals("getMatrices") && call.getArguments().isEmpty()
						&& k.sigTo.getOrDefault("DrawContext.getMatrices", Set.of()).contains("getMatrices()Lnet/minecraft/client/util/math/MatrixStack;")) {
					a.backportGuiMatrices(p, method, member);
				}
				if (member.toString().equals("InputUtil.isKeyPressed") && a.imported("net.minecraft.client.util.InputUtil") && !a.shadowed("InputUtil")
						&& k.sigTo.getOrDefault("InputUtil.isKeyPressed", Set.of()).contains("isKeyPressed(Lnet/minecraft/client/util/Window;I)Z")
						&& call.getArguments().size() == 2) {
					String window = a.windowFor(call.getArguments().getFirst(), method);
					if (window != null) { a.edit(call.getArguments().getFirst(), window); }
				}
			}
			return a.withEdits();
		}
		String opaqueTextColor(ExpressionTree color) {
			if (color instanceof LiteralTree literal && literal.getValue() instanceof Integer value) {
				return (value & 0xFC000000) == 0 ? String.format("0x%08X", value | 0xFF000000) : null;
			}
			boolean stable = color instanceof IdentifierTree || color instanceof MemberSelectTree member
					&& member.getExpression() instanceof IdentifierTree;
			if (!stable) return null;
			String value = text(color);
			return "((" + value + " & 0xFC000000) == 0 ? (" + value + " | 0xFF000000) : " + value + ")";
		}
		static String apply(Known k, Path file, String source, List<Err> errors) throws IOException {
			if (!"yarn".equals(k.names)) { return source; }
			ApiFix a = new ApiFix(file, source);
			String[] lines = source.split("\r?\n", -1);
			for (Err e : errors) {
				// A preceding rule/import can invalidate a supplied log. Rebuild before using those positions.
				if (e.caret() < 0 || e.line() < 1 || e.line() > lines.length || !lines[e.line() - 1].strip().equals(e.source().strip())) { continue; }
				long at = a.unit.getLineMap().getPosition(e.line(), 1) + e.caret();
				for (TreePath p : a.paths) {
					Tree tree = p.getLeaf();
					if (a.start(tree) > at || a.end(tree) <= at) { continue; }
					TreePath mp = a.enclosing(p, MethodTree.class);
					if (mp == null) { continue; }
					MethodTree method = (MethodTree) mp.getLeaf();
					if (tree instanceof MemberSelectTree field && field.toString().equals("MinecraftClient.IS_SYSTEM_MAC")
							&& a.imported("net.minecraft.client.MinecraftClient") && k.fieldGone.contains("IS_SYSTEM_MAC")
							&& "1.21.9".equals(k.toVersion)) {
						a.edit(field, "net.minecraft.client.input.SystemKeycodes.IS_MAC_OS");
					}
					if (tree instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree member) {
						String name = member.getIdentifier().toString();
						String type = Known.locationType(e.location());
						if ("1.21.5".equals(k.toVersion) && e.message().contains("RenderPipeline cannot be converted to Function<Identifier,RenderLayer>")
								&& a.drawContext(method) && a.imported("net.minecraft.client.gl.RenderPipelines") && !a.shadowed("RenderPipelines")
								&& (name.equals("drawGuiTexture") || name.equals("drawTexture"))
								&& k.sigTo.getOrDefault("DrawContext." + name, Set.of()).stream().anyMatch(s -> s.contains("Ljava/util/function/Function;"))
								&& !call.getArguments().isEmpty() && call.getArguments().getFirst().toString().equals("RenderPipelines.GUI_TEXTURED")
								&& method.getParameters().stream().anyMatch(v -> v.getType().toString().equals("DrawContext") && v.getName().contentEquals(member.getExpression().toString()))) {
							a.edit(call.getArguments().getFirst(), "net.minecraft.client.render.RenderLayer::getGuiTextured");
						}
						if ("1.21.5".equals(k.toVersion) && e.message().contains("MatrixStack cannot be converted to Matrix3x2fStack")
								&& name.equals("getMatrices") && a.drawContext(method)
								&& k.sigTo.getOrDefault("DrawContext.getMatrices", Set.of()).contains("getMatrices()Lnet/minecraft/client/util/math/MatrixStack;")) {
							a.backportGuiMatrices(p, method, member);
						}
						if (e.message().equals("cannot find symbol") && e.symbol() != null && e.symbol().startsWith("method " + name + "(")
								&& call.getArguments().isEmpty() && a.entityType(type, member.getExpression(), method)) {
							String to = name.equals("getEntityWorld") && "1.21.6".equals(k.toVersion) ? "getWorld"
									: name.equals("getWorld") && "1.21.9".equals(k.toVersion) ? "getEntityWorld" : null;
							if (to != null && k.methodGone.contains(name)) { a.edits.add(new Edit(a.end(member) - name.length(), a.end(member), to)); }
						}
						if (member.toString().equals("InputUtil.isKeyPressed") && a.imported("net.minecraft.client.util.InputUtil") && !a.shadowed("InputUtil")
								&& k.sigTo.getOrDefault("InputUtil.isKeyPressed", Set.of()).contains("isKeyPressed(Lnet/minecraft/client/util/Window;I)Z")
								&& e.message().contains("long cannot be converted to Window") && call.getArguments().size() == 2) {
							ExpressionTree arg = call.getArguments().getFirst();
							String window = a.windowFor(arg, method);
							if (window != null) { a.edit(arg, window); }
						}
						if (member.toString().equals("RenderSystem.setShaderColor") && e.message().equals("cannot find symbol")
								&& e.symbol() != null && e.symbol().startsWith("method setShaderColor(")
								&& a.imported("com.mojang.blaze3d.systems.RenderSystem") && a.drawContext(method)
								&& k.sigTo.getOrDefault("DrawContext.getMatrices", Set.of()).contains("getMatrices()Lorg/joml/Matrix3x2fStack;")
								&& call.getArguments().size() == 4 && call.getArguments().stream().allMatch(x -> x instanceof LiteralTree l
									&& l.getValue() instanceof Number n && n.doubleValue() == 1.0)
								&& p.getParentPath().getLeaf() instanceof ExpressionStatementTree statement) { a.edit(statement, ""); }
						if (name.equals("getMatrices") && e.message().contains("Matrix3x2fStack cannot be converted to MatrixStack")
								&& a.drawContext(method)) { a.guiMatrices(p, method, member); }
					}
				}
			}
			return a.withEdits();
		}
		String withEdits() {
			// Deduplicate diagnostics/overlapping selections, then edit from right to left.
			List<Edit> unique = new ArrayList<>(new LinkedHashSet<>(edits));
			unique.sort(Comparator.comparingInt(Edit::start).reversed());
			int boundary = source.length();
			String result = source;
			for (Edit e : unique) {
				if (e.start() < 0 || e.end() > boundary) { continue; }
				result = result.substring(0, e.start()) + e.text() + result.substring(e.end()); boundary = e.start();
			}
			return result;
		}
		void backportGuiMatrices(TreePath callPath, MethodTree method, MemberSelectTree getMatrices) {
			Tree parent = callPath.getParentPath().getLeaf();
			String name = parent instanceof AssignmentTree assign ? assign.getVariable().toString() : "";
			List<VariableTree> vars = variables(method, name);
			if (vars.size() != 1 || !vars.getFirst().getType().toString().equals("Matrix3x2fStack")
					|| !imported("org.joml.Matrix3x2fStack")) { return; }
			String context = getMatrices.getExpression().toString();
			if (method.getParameters().stream().noneMatch(v -> v.getName().contentEquals(context) && v.getType().toString().equals("DrawContext"))) { return; }
			List<Edit> planned = new ArrayList<>();
			planned.add(new Edit(start(vars.getFirst().getType()), end(vars.getFirst().getType()), "net.minecraft.client.util.math.MatrixStack"));
			for (TreePath p : inside(method)) {
				if (!(p.getLeaf() instanceof IdentifierTree id) || !id.getName().contentEquals(name)) { continue; }
				Tree up = p.getParentPath().getLeaf();
				if (up instanceof AssignmentTree assign && assign.getVariable() == id && assign.getExpression() == callPath.getLeaf()) { continue; }
				if (up instanceof VariableTree variable && variable == vars.getFirst()) { continue; }
				if (!(up instanceof MemberSelectTree member) || member.getExpression() != id
						|| !(p.getParentPath().getParentPath().getLeaf() instanceof MethodInvocationTree call)) { return; }
				String operation = member.getIdentifier().toString();
				List<? extends ExpressionTree> args = call.getArguments();
				String replacement;
				if ((operation.equals("pushMatrix") || operation.equals("popMatrix")) && args.isEmpty()) {
					replacement = name + "." + (operation.equals("pushMatrix") ? "push" : "pop") + "()";
				} else if ((operation.equals("translate") || operation.equals("scale")) && args.size() == 2) {
					replacement = name + "." + operation + "(" + text(args.get(0)) + ", " + text(args.get(1))
							+ (operation.equals("translate") ? ", 0" : ", 1") + ")";
				} else { return; }
				planned.add(new Edit(start(call), end(call), replacement));
			}
			edits.addAll(planned);
		}
		String windowFor(ExpressionTree argument, MethodTree method) {
			ExpressionTree value = argument;
			if (argument instanceof IdentifierTree id) {
				List<VariableTree> vars = variables(method, id.getName().toString());
				if (vars.size() != 1 || vars.getFirst().getInitializer() == null) { return null; }
				value = vars.getFirst().getInitializer();
				for (TreePath p : inside(method)) {
					Tree t = p.getLeaf();
					if (t instanceof AssignmentTree assign && assign.getVariable().toString().equals(id.toString())
							|| t instanceof CompoundAssignmentTree compound && compound.getVariable().toString().equals(id.toString())
							|| t instanceof UnaryTree unary && unary.getExpression().toString().equals(id.toString())
								&& Set.of(Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT, Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT).contains(t.getKind())) { return null; }
				}
			}
			if (value instanceof MethodInvocationTree call && call.getArguments().isEmpty()
					&& call.getMethodSelect().toString().equals("MinecraftClient.getInstance().getWindow().getHandle")
					&& imported("net.minecraft.client.MinecraftClient") && !shadowed("MinecraftClient")) { return "MinecraftClient.getInstance().getWindow()"; }
			return null;
		}
		void guiMatrices(TreePath callPath, MethodTree method, MemberSelectTree getMatrices) {
			// Only a local GUI stack with known uses. Passing it elsewhere or mixing in 3D operations needs a guided port.
			Tree parent = callPath.getParentPath().getLeaf();
			String name = parent instanceof AssignmentTree assign ? assign.getVariable().toString()
					: parent instanceof VariableTree v ? v.getName().toString() : "";
			List<VariableTree> vars = variables(method, name);
			if (vars.size() != 1 || !vars.getFirst().getType().toString().equals("MatrixStack")
					|| !imported("net.minecraft.client.util.math.MatrixStack")) { return; }
			String context = getMatrices.getExpression().toString();
			if (method.getParameters().stream().noneMatch(v -> v.getName().contentEquals(context) && v.getType().toString().equals("DrawContext"))) { return; }
			List<Edit> planned = new ArrayList<>();
			VariableTree variable = vars.getFirst();
			if (method.getParameters().contains(variable)) { return; }
			if (variable.getInitializer() != null && variable.getInitializer() != callPath.getLeaf()) { return; }
			planned.add(new Edit(start(variable.getType()), end(variable.getType()), "org.joml.Matrix3x2fStack"));
			for (TreePath p : inside(method)) {
				if (!(p.getLeaf() instanceof IdentifierTree id) || !id.getName().contentEquals(name)) { continue; }
				Tree up = p.getParentPath().getLeaf();
				if (up instanceof AssignmentTree assign && assign.getVariable() == id && assign.getExpression() == callPath.getLeaf()) { continue; }
				if (!(up instanceof MemberSelectTree member) || member.getExpression() != id
						|| !(p.getParentPath().getParentPath().getLeaf() instanceof MethodInvocationTree call)) { return; }
				String operation = member.getIdentifier().toString();
				List<? extends ExpressionTree> args = call.getArguments();
				String replacement;
				if ((operation.equals("push") || operation.equals("pop")) && args.isEmpty()) { replacement = name + "." + operation + "Matrix()"; }
				else if ((operation.equals("translate") || operation.equals("scale")) && args.size() == 3
						&& (args.get(2) instanceof IdentifierTree || args.get(2) instanceof LiteralTree)) {
					// GUI draw order replaces Z depth. Keep X/Y unchanged and never discard a call with side effects.
					replacement = name + "." + operation + "(" + text(args.get(0)) + ", " + text(args.get(1)) + ")";
				} else { return; }
				planned.add(new Edit(start(call), end(call), replacement));
			}
			edits.addAll(planned);
		}
	}

	static String unq(String s) { return s.replace("\\\"", "\"").replace("\\\\", "\\"); }

	// ------------------------------------------------------------------ learn from real ports

	/**
	 * Reads unified diffs of real ports of the same jump (other mods' "old version branch ->
	 * new version branch") and keeps the line rewrites that touch something the map says changed:
	 * the smallest differing span of each changed line pair, e.g.
	 * "RenderLayer::getGuiTextured" -> "RenderPipelines.GUI_TEXTURED", with the imports that file
	 * added for it. Writes one rule per line, most-seen first.
	 */
	static final class Learn {
		static final Pattern TOKEN = Pattern.compile("\\w+|::|->|\\S");
		static final String SEP = "\u0001";

		static void run(Path mapFile, List<String> diffs, Path out) throws IOException {
			Known k = Known.load(mapFile);
			Set<String> changed = new HashSet<>();
			changed.addAll(k.sigNames); changed.addAll(k.methodGone); changed.addAll(k.fieldGone);
			changed.addAll(k.methodTo.keySet()); changed.addAll(k.fieldTo.keySet());
			changed.addAll(k.classBySimple.keySet()); changed.addAll(k.removedClassBySimple.keySet());
			Map<String, int[]> seen = new LinkedHashMap<>();
			Map<String, Set<String>> sources = new HashMap<>(), imports = new HashMap<>();
			for (String d : diffs) {
				Path p = Path.of(d);
				String source = p.getFileName().toString().replaceAll("\\.diff$", "");
				List<String> removed = new ArrayList<>(), added = new ArrayList<>(), fileImports = new ArrayList<>();
				List<String[]> pairsOfFile = new ArrayList<>();
				boolean java = false;
				for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
					if (line.startsWith("diff --git")) {
						flush(removed, added, pairsOfFile);
						record(pairsOfFile, fileImports, changed, seen, sources, imports, source);
						pairsOfFile.clear(); fileImports.clear();
						java = line.endsWith(".java");
						continue;
					}
					if (!java || line.startsWith("+++") || line.startsWith("---")) { continue; }
					if (line.startsWith("-")) {
						if (!added.isEmpty()) { flush(removed, added, pairsOfFile); }
						removed.add(line.substring(1));
					} else if (line.startsWith("+")) {
						String a = line.substring(1);
						if (a.startsWith("import ")) { fileImports.add(a.replaceAll("^import\\s+|;\\s*$", "")); }
						added.add(a);
					} else {
						flush(removed, added, pairsOfFile);
					}
				}
				flush(removed, added, pairsOfFile);
				record(pairsOfFile, fileImports, changed, seen, sources, imports, source);
			}
			List<String> keys = new ArrayList<>(seen.keySet());
			keys.sort((a, b) -> seen.get(b)[0] - seen.get(a)[0]);
			StringBuilder sb = new StringBuilder();
			for (String key : keys) {
				String[] ft = key.split(SEP, 2);
				sb.append("{ \"from\": ").append(Diff.q(ft[0])).append(", \"to\": ").append(Diff.q(ft[1]))
						.append(", \"seen\": ").append(seen.get(key)[0])
						.append(", \"sources\": [").append(String.join(", ", sources.get(key).stream().map(Diff::q).toList())).append("]")
						.append(", \"imports\": [").append(String.join(", ", imports.getOrDefault(key, Set.of()).stream().map(Diff::q).toList())).append("] }\n");
			}
			Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
			System.out.println("learned " + keys.size() + " rewrite(s) from " + diffs.size() + " port(s) -> " + out);
			keys.stream().limit(15).forEach(x -> System.out.println("  " + seen.get(x)[0] + "x  " + x.replace(SEP, "  ->  ")));
		}

		/** Pairs each removed line with its most similar added line. */
		static void flush(List<String> removed, List<String> added, List<String[]> pairs) {
			Set<Integer> used = new HashSet<>();
			for (String r : removed) {
				int best = -1; double score = 0.5;
				for (int i = 0; i < added.size(); i++) {
					if (used.contains(i)) { continue; }
					double s = similarity(r, added.get(i));
					if (s > score) { score = s; best = i; }
				}
				if (best >= 0) { used.add(best); pairs.add(new String[] { r, added.get(best) }); }
			}
			removed.clear(); added.clear();
		}

		static double similarity(String a, String b) {
			Set<String> ta = new HashSet<>(tokens(a)), tb = new HashSet<>(tokens(b));
			if (ta.isEmpty() || tb.isEmpty()) { return 0; }
			Set<String> both = new HashSet<>(ta); both.retainAll(tb);
			Set<String> any = new HashSet<>(ta); any.addAll(tb);
			return (double) both.size() / any.size();
		}

		static List<String> tokens(String s) {
			List<String> out = new ArrayList<>();
			Matcher m = TOKEN.matcher(s);
			while (m.find()) { out.add(m.group()); }
			return out;
		}

		static void record(List<String[]> pairs, List<String> fileImports, Set<String> changed, Map<String, int[]> seen,
				Map<String, Set<String>> sources, Map<String, Set<String>> imports, String source) {
			for (String[] pr : pairs) {
				String[] span = span(pr[0], pr[1]);
				if (span == null) { continue; }
				boolean anchored = false;
				for (String t : tokens(span[0])) { if (changed.contains(t)) { anchored = true; break; } }
				if (!anchored || span[0].contains("\"") || span[0].length() > 80 || span[1].length() > 100) { continue; }
				// Bare names lose their declaring type; published mappings already cover certain renames.
				// Keep self-contained qualified expressions, never arbitrary fragments of a mod's code.
				if (!span[0].matches("[A-Z]\\w*(?:\\.|::).*") || !span[1].matches("[A-Z]\\w*(?:\\.|::).*")
						|| !balanced(span[0]) || !balanced(span[1])) { continue; }
				if (fileImports.stream().anyMatch(im -> im.contains(".impl.") && tokens(span[1]).contains(simple(im)))) { continue; }
				if (introducesLocalName(span, fileImports)) { continue; }
				String key = span[0] + SEP + span[1];
				seen.computeIfAbsent(key, x -> new int[1])[0]++;
				sources.computeIfAbsent(key, x -> new TreeSet<>()).add(source);
				for (String im : fileImports) {
					String simpleName = im.substring(im.lastIndexOf('.') + 1);
					if (Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(span[1]).find()) {
						imports.computeIfAbsent(key, x -> new TreeSet<>()).add(im);
					}
				}
			}
		}

		static final Set<String> KEYWORDS = Set.of("new", "this", "super", "null", "true", "false", "return", "instanceof",
				"int", "long", "float", "double", "boolean", "byte", "char", "short", "void", "var", "final", "class");

		/**
		 * True when the new side names something the target file cannot see: not in the old side,
		 * not imported by that port, not reached through "." or "::". Those come from that mod's own
		 * code (a local constant, a helper), so the rewrite would not compile anywhere else.
		 */
		static boolean introducesLocalName(String[] span, List<String> fileImports) {
			Set<String> old = new HashSet<>(tokens(span[0]));
			Set<String> imported = new HashSet<>();
			for (String im : fileImports) { imported.add(im.substring(im.lastIndexOf('.') + 1)); }
			List<String> t = tokens(span[1]);
			for (int i = 0; i < t.size(); i++) {
				String x = t.get(i);
				if (x.equals("this") || x.equals("super")) { return true; }
				if (!x.matches("[A-Za-z_]\\w*") || old.contains(x) || imported.contains(x) || KEYWORDS.contains(x)) { continue; }
				if (i > 0 && (t.get(i - 1).equals(".") || t.get(i - 1).equals("::"))) { continue; }
				return true;
			}
			return false;
		}

		static boolean balanced(String expression) {
			Deque<String> open = new ArrayDeque<>();
			for (String t : tokens(expression)) {
				if (t.equals("(") || t.equals("[")) { open.push(t); }
				if (t.equals(")") || t.equals("]")) {
					if (open.isEmpty() || !open.pop().equals(t.equals(")") ? "(" : "[")) { return false; }
				}
			}
			return open.isEmpty();
		}

		/** The smallest differing source span between two lines, cut on token boundaries. */
		static String[] span(String a, String b) {
			List<int[]> pa = positions(a), pb = positions(b);
			int pre = 0;
			while (pre < pa.size() && pre < pb.size() && tok(a, pa.get(pre)).equals(tok(b, pb.get(pre)))) { pre++; }
			int suf = 0;
			while (suf < pa.size() - pre && suf < pb.size() - pre
					&& tok(a, pa.get(pa.size() - 1 - suf)).equals(tok(b, pb.get(pb.size() - 1 - suf)))) { suf++; }
			if (pre == pa.size() && pre == pb.size()) { return null; }
			if (pre >= pa.size() - suf) { return null; }
			int as = pa.get(pre)[0], ae = pa.get(pa.size() - 1 - suf)[1];
			String na = a.substring(as, ae);
			String nb = pre < pb.size() - suf ? b.substring(pb.get(pre)[0], pb.get(pb.size() - 1 - suf)[1]) : "";
			return new String[] { na, nb };
		}

		static List<int[]> positions(String s) {
			List<int[]> out = new ArrayList<>();
			Matcher m = TOKEN.matcher(s);
			while (m.find()) { out.add(new int[] { m.start(), m.end() }); }
			return out;
		}

		static String tok(String s, int[] p) { return s.substring(p[0], p[1]); }
	}
}
