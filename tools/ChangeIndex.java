import java.io.*;
import java.lang.classfile.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * ChangeIndex - what changed in the game between every pair of neighbouring Minecraft releases,
 * written as compact data a port router (and the porting-map page) can read.
 *
 * Names are Mojang's official names throughout. Obfuscated releases (1.14.4 to 1.21.11) are read
 * from the client jar plus Mojang's published mappings; 26.1 and later ship unobfuscated, so the
 * jar's own names are used. Two obfuscated releases are joined through Fabric's intermediary ids,
 * which stay the same across versions, so a rename is linked, not guessed ("exact"). A hop that
 * touches an unobfuscated release has no shared id: classes and members are matched by name, and
 * a rename or move there is a best guess ("by-name"), labelled as such in the data.
 *
 * Single file plus tools/VersionMap.java (mapping parsers and the cached downloader), JDK 25:
 *   java tools/ChangeIndex.java [--out coverage/changes] [--cache .modaccel/change-index]
 *                               [--first 1.14.4] [--last 26.3] [--pair A B]...
 *
 * Without --pair every neighbouring release pair from --first to --last is written, plus
 * versions.json. With --pair only those pairs are written (hub-to-hub hops).
 * Jars and mappings are cached and checked against Mojang's sha1; nothing downloaded is committed.
 * Exit: 0 ok, 2 usage or I/O error.
 */
public final class ChangeIndex {
	static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	static final int ACC_PRIVATE = 0x0002, ACC_PROTECTED = 0x0004, ACC_PUBLIC = 0x0001, ACC_BRIDGE = 0x0040, ACC_SYNTHETIC = 0x1000;

	public static void main(String[] args) throws Exception {
		Path out = Path.of("coverage", "changes"), cache = Path.of(".modaccel", "change-index");
		String first = "1.14.4", last = null;
		List<String[]> pairs = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--out" -> out = Path.of(args[++i]);
				case "--cache" -> cache = Path.of(args[++i]);
				case "--first" -> first = args[++i];
				case "--last" -> last = args[++i];
				case "--pair" -> pairs.add(new String[] { args[++i], args[++i] });
				default -> { System.err.println("usage: java tools/ChangeIndex.java [--out dir] [--cache dir] [--first v] [--last v] [--pair A B]..."); System.exit(2); }
			}
		}
		VersionMap.cache = cache.toAbsolutePath();
		Files.createDirectories(out);
		long t0 = System.nanoTime();
		// The manifest is always fetched fresh so a new release shows up.
		Files.deleteIfExists(VersionMap.cache.resolve("version_manifest_v2.json"));
		String manifest = VersionMap.fetchText(MANIFEST, "version_manifest_v2.json");
		List<Release> releases = Release.parse(manifest);
		List<String> ids = releases.stream().map(Release::id).toList();
		if (last == null) { last = ids.get(ids.size() - 1); }
		int a = ids.indexOf(first), b = ids.indexOf(last);
		if (a < 0 || b < 0 || b <= a) { throw new IOException("unknown release range " + first + " .. " + last); }
		List<String> range = ids.subList(a, b + 1);

		Set<String> needed = new LinkedHashSet<>();
		if (pairs.isEmpty()) { needed.addAll(range); } else { pairs.forEach(p -> { needed.add(p[0]); needed.add(p[1]); }); }
		prefetch(releases, needed);

		Map<String, Snap> held = new HashMap<>();
		List<String> versionRows = new ArrayList<>();
		if (pairs.isEmpty()) {
			for (int i = 0; i + 1 < range.size(); i++) { pairs.add(new String[] { range.get(i), range.get(i + 1) }); }
		}
		for (String[] p : pairs) {
			Snap sa = held.computeIfAbsent(p[0], v -> load(releases, v));
			Snap sb = held.computeIfAbsent(p[1], v -> load(releases, v));
			Diff d = Diff.of(sa, sb);
			Path file = out.resolve(p[0] + "__" + p[1] + ".json");
			Files.writeString(file, d.json(), StandardCharsets.UTF_8);
			System.out.printf("%-8s -> %-8s %-7s classes +%d -%d ~%d  methods +%d -%d ~%d sig %d  fields +%d -%d ~%d  breaks %.1f%%  %,d bytes%n",
					p[0], p[1], d.exact ? "exact" : "by-name", d.c("classesAdded"), d.c("classesRemoved"), d.c("classesRenamed"),
					d.c("methodsAdded"), d.c("methodsRemoved"), d.c("methodsRenamed"), d.c("methodsChanged"), d.c("fieldsAdded"),
					d.c("fieldsRemoved"), d.c("fieldsRenamed"), 100.0 * d.breakingShare(), Files.size(file));
			held.keySet().retainAll(Set.of(p[1]));
			held.computeIfAbsent(p[1], v -> sb);
		}
		if (pairs.size() == range.size() - 1 && pairs.get(0)[0].equals(first)) {
			// versions.json: every release, the ones indexed with their size, the older ones blocked.
			Map<String, int[]> sizes = SIZES;
			StringBuilder sb = new StringBuilder("{\n  \"source\": ").append(q(MANIFEST)).append(",\n  \"versions\": [\n");
			for (int i = 0; i < releases.size(); i++) {
				Release r = releases.get(i);
				sb.append("    { \"id\": ").append(q(r.id())).append(", \"released\": ").append(q(r.time().substring(0, 10)));
				int[] s = sizes.get(r.id());
				if (s != null) {
					sb.append(", \"status\": \"indexed\", \"names\": ").append(q(s[3] == 1 ? "official (Mojang mappings)" : "official (unobfuscated jar)"))
							.append(", \"classes\": ").append(s[0]).append(", \"methods\": ").append(s[1]).append(", \"fields\": ").append(s[2]);
				} else if (i < a) {
					sb.append(", \"status\": \"blocked\", \"reason\": \"Mojang published no official mappings before 1.14.4; this release would need MCP or other legacy community mappings, which the index does not use.\"");
				} else {
					sb.append(", \"status\": \"not indexed\"");
				}
				sb.append(i + 1 < releases.size() ? " },\n" : " }\n");
			}
			sb.append("  ]\n}\n");
			Files.writeString(out.resolve("versions.json"), sb.toString(), StandardCharsets.UTF_8);
		}
		System.out.printf("done in %.0fs%n", (System.nanoTime() - t0) / 1e9);
	}

	static final Map<String, int[]> SIZES = new ConcurrentHashMap<>(); // id -> classes, methods, fields, obfuscated

	// ------------------------------------------------------------------ releases and downloads

	record Release(String id, String url, String time) {
		static List<Release> parse(String manifest) {
			List<Release> list = new ArrayList<>();
			int at = 0;
			while ((at = manifest.indexOf("\"id\"", at)) >= 0) {
				int end = manifest.indexOf('}', at);
				String obj = manifest.substring(at, end);
				String id = VersionMap.jsonString(obj, "id", 0), type = VersionMap.jsonString(obj, "type", 0);
				if ("release".equals(type)) { list.add(new Release(id, VersionMap.jsonString(obj, "url", 0), VersionMap.jsonString(obj, "releaseTime", 0))); }
				at = end;
			}
			list.sort(Comparator.comparing(Release::time));
			return list;
		}
	}

	static Release find(List<Release> releases, String id) {
		return releases.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("no release " + id));
	}

	/** The url and sha1 of one entry under "downloads" in a version json. */
	static String[] download(String versionJson, String key) {
		int d = versionJson.indexOf("\"downloads\"");
		int k = d < 0 ? -1 : versionJson.indexOf("\"" + key + "\"", d);
		if (k < 0) { return null; }
		int end = versionJson.indexOf('}', k);
		String obj = versionJson.substring(k, end);
		return new String[] { VersionMap.jsonString(obj, "url", 0), VersionMap.jsonString(obj, "sha1", 0) };
	}

	static Path verified(String[] dl, String name) throws Exception {
		Path p = VersionMap.fetch(dl[0], name);
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(p)));
		if (!sha.equalsIgnoreCase(dl[1])) { Files.delete(p); throw new IOException(name + " failed its sha1 check"); }
		return p;
	}

	static void prefetch(List<Release> releases, Set<String> ids) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(6);
		List<Future<?>> jobs = new ArrayList<>();
		for (String id : ids) {
			jobs.add(pool.submit(() -> {
				Release r = find(releases, id);
				String vj = VersionMap.fetchText(r.url(), "version-" + id + ".json");
				verified(download(vj, "client"), "client-" + id + ".jar");
				String[] m = download(vj, "client_mappings");
				if (m != null) {
					verified(m, "client-" + id + ".txt");
					VersionMap.fetch("https://maven.fabricmc.net/net/fabricmc/intermediary/" + id + "/intermediary-" + id + "-v2.jar", "intermediary-" + id + "-v2.jar");
				}
				return null;
			}));
		}
		for (Future<?> f : jobs) { f.get(); }
		pool.shutdown();
	}

	// ------------------------------------------------------------------ one release

	/** A member: official name and descriptor, plus the key and descriptor that compare across versions. */
	record Mem(String name, String desc, String key, String cmpDesc, int flags) {
		boolean isPrivate() { return (flags & ACC_PRIVATE) != 0; }
	}

	static final class Cls {
		final String named, key;
		final int flags;
		final Map<String, Mem> methods = new LinkedHashMap<>(), fields = new LinkedHashMap<>();
		Cls(String named, String key, int flags) { this.named = named; this.key = key; this.flags = flags; }
	}

	/** Every class of one release. keys are intermediary ids for obfuscated releases, names otherwise. */
	static final class Snap {
		final String version;
		final boolean obfuscated;
		final Map<String, Cls> classes = new LinkedHashMap<>();
		Snap(String version, boolean obfuscated) { this.version = version; this.obfuscated = obfuscated; }
	}

	static Snap load(List<Release> releases, String id) {
		try {
			Release r = find(releases, id);
			String vj = VersionMap.fetchText(r.url(), "version-" + id + ".json");
			Path jar = verified(download(vj, "client"), "client-" + id + ".jar");
			String[] m = download(vj, "client_mappings");
			VersionMap.Proguard pg = null;
			VersionMap.Tiny it = null;
			if (m != null) {
				pg = VersionMap.Proguard.parse(Files.readString(verified(m, "client-" + id + ".txt"), StandardCharsets.UTF_8));
				Path inter = VersionMap.fetch("https://maven.fabricmc.net/net/fabricmc/intermediary/" + id + "/intermediary-" + id + "-v2.jar", "intermediary-" + id + "-v2.jar");
				it = VersionMap.Tiny.parse(VersionMap.zipEntry(inter, "mappings/mappings.tiny"));
			}
			Snap s = new Snap(id, pg != null);
			int methods = 0, fields = 0;
			try (ZipFile z = new ZipFile(jar.toFile())) {
				for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
					ZipEntry ze = e.nextElement();
					String n = ze.getName();
					if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("package-info.class") || n.endsWith("module-info.class")) { continue; }
					ClassModel cm = ClassFile.of().parse(z.getInputStream(ze).readAllBytes());
					String obf = cm.thisClass().asInternalName();
					String named = pg == null ? obf : pg.classes.getOrDefault(obf, obf);
					if (!named.startsWith("net/minecraft/") && !named.startsWith("com/mojang/")) { continue; }
					if (anonymous(named)) { continue; }
					String key = it == null ? named : it.classes.getOrDefault(obf, obf);
					int cf = cm.flags().flagsMask();
					if ((cf & ACC_SYNTHETIC) != 0) { continue; }
					Cls c = new Cls(named, key, cf);
					for (MethodModel mm : cm.methods()) {
						int f = mm.flags().flagsMask();
						String mn = mm.methodName().stringValue(), md = mm.methodType().stringValue();
						if ((f & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0 || mn.contains("$") || mn.equals("<clinit>")) { continue; }
						String name = mn, desc = md, mkey, cmp;
						if (pg != null) {
							name = pg.methods.getOrDefault(obf + "." + mn + md, mn);
							desc = VersionMap.Tiny.remapDesc(md, pg.classes);
							String inter = it.methods.get(obf + "." + mn + md);
							cmp = VersionMap.Tiny.remapDesc(md, it.classes);
							mkey = inter != null && inter.startsWith("method_") ? inter : name + cmp;
						} else {
							cmp = md;
							mkey = mn + md;
						}
						c.methods.put(mkey, new Mem(name, desc, mkey, cmp, f));
						if ((f & ACC_PRIVATE) == 0) { methods++; }
					}
					for (FieldModel fm : cm.fields()) {
						int f = fm.flags().flagsMask();
						String fn = fm.fieldName().stringValue(), fd = fm.fieldType().stringValue();
						if ((f & ACC_SYNTHETIC) != 0 || fn.contains("$")) { continue; }
						String name = fn, desc = fd, fkey, cmp;
						if (pg != null) {
							name = pg.fields.getOrDefault(obf + "." + fn, fn);
							desc = VersionMap.Tiny.remapDesc(fd, pg.classes);
							String inter = it.fields.get(obf + "." + fn);
							cmp = VersionMap.Tiny.remapDesc(fd, it.classes);
							fkey = inter != null && inter.startsWith("field_") ? inter : name;
						} else {
							cmp = fd;
							fkey = fn;
						}
						c.fields.put(fkey, new Mem(name, desc, fkey, cmp, f));
						if ((f & ACC_PRIVATE) == 0) { fields++; }
					}
					s.classes.put(key, c);
				}
			}
			SIZES.put(id, new int[] { s.classes.size(), methods, fields, s.obfuscated ? 1 : 0 });
			System.out.printf("  read %s: %,d classes, %,d methods, %,d fields (not private)%n", id, s.classes.size(), methods, fields);
			return s;
		} catch (Exception e) {
			throw new RuntimeException("cannot read Minecraft " + id + ": " + e.getMessage(), e);
		}
	}

	/** Foo$1 and Foo$1Local are compiler-made classes no mod can name. */
	static boolean anonymous(String internal) {
		int d = internal.lastIndexOf('$');
		return d >= 0 && d + 1 < internal.length() && Character.isDigit(internal.charAt(d + 1));
	}

	// ------------------------------------------------------------------ readable names

	static String dots(String internal) { return internal.replace('/', '.'); }

	static String simpleType(String t) {
		int s = t.lastIndexOf('/');
		return t.substring(s + 1).replace('$', '.');
	}

	/** A descriptor written with simple type names: (ItemStack,int)boolean. */
	static String readable(String desc) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		boolean params = desc.startsWith("(");
		if (params) { sb.append('('); i = 1; }
		boolean firstParam = true;
		while (i < desc.length()) {
			char ch = desc.charAt(i);
			if (ch == ')') { sb.append(')'); i++; firstParam = true; params = false; continue; }
			int dims = 0;
			while (desc.charAt(i) == '[') { dims++; i++; }
			String type;
			char k = desc.charAt(i);
			if (k == 'L') {
				int semi = desc.indexOf(';', i);
				type = simpleType(desc.substring(i + 1, semi));
				i = semi + 1;
			} else {
				type = switch (k) { case 'V' -> "void"; case 'Z' -> "boolean"; case 'B' -> "byte"; case 'C' -> "char"; case 'S' -> "short";
					case 'I' -> "int"; case 'J' -> "long"; case 'F' -> "float"; case 'D' -> "double"; default -> String.valueOf(k); };
				i++;
			}
			if (params && !firstParam) { sb.append(','); }
			firstParam = false;
			sb.append(type).append("[]".repeat(dims));
		}
		return sb.toString();
	}

	static String sig(Mem m, boolean method) {
		String s = method ? m.name() + readable(m.desc()) : m.name() + ":" + readable(m.desc());
		return m.isPrivate() ? "private " + s : s;
	}

	static String q(String s) {
		if (s == null) { return "null"; }
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') { sb.append('\\').append(c); } else if (c < 0x20) { sb.append(String.format("\\u%04x", (int) c)); } else { sb.append(c); }
		}
		return sb.append('"').toString();
	}

	// ------------------------------------------------------------------ the diff

	static final class OwnerChanges {
		final String owner;
		String renamedTo;
		final Map<String, List<String>> lists = new TreeMap<>();
		OwnerChanges(String owner) { this.owner = owner; }
		void add(String list, String item) { lists.computeIfAbsent(list, x -> new ArrayList<>()).add(item); }
	}

	static final class Diff {
		String from, to;
		boolean exact;
		final List<String> classesAdded = new ArrayList<>(), classesRemoved = new ArrayList<>();
		final List<String[]> classesRenamed = new ArrayList<>();
		final Map<String, OwnerChanges> owners = new TreeMap<>();
		final Map<String, Integer> counts = new LinkedHashMap<>();
		int oldSize, broken;

		int c(String k) { return counts.getOrDefault(k, 0); }
		void count(String k, boolean api) { counts.merge(k, 1, Integer::sum); if (api) { counts.merge(k + "Api", 1, Integer::sum); } }
		double breakingShare() { return oldSize == 0 ? 0 : (double) broken / oldSize; }

		OwnerChanges owner(String o) { return owners.computeIfAbsent(o, OwnerChanges::new); }

		static Diff of(Snap a, Snap b) {
			Diff d = new Diff();
			d.from = a.version; d.to = b.version;
			d.exact = a.obfuscated && b.obfuscated;
			for (String k : List.of("classesAdded", "classesRemoved", "classesRenamed", "methodsAdded", "methodsRemoved", "methodsRenamed",
					"methodsChanged", "fieldsAdded", "fieldsRemoved", "fieldsRenamed", "fieldsChanged")) { d.counts.put(k, 0); }
			// Re-key both sides by name when one side has no intermediary.
			Map<String, Cls> A = new LinkedHashMap<>(), B = new LinkedHashMap<>();
			for (Cls c : a.classes.values()) { A.put(d.exact ? c.key : c.named, c); }
			for (Cls c : b.classes.values()) { B.put(d.exact ? c.key : c.named, c); }
			for (Cls c : A.values()) {
				if ((c.flags & ACC_PRIVATE) == 0) { d.oldSize++; }
				for (Mem m : c.methods.values()) { if (!m.isPrivate()) { d.oldSize++; } }
				for (Mem m : c.fields.values()) { if (!m.isPrivate()) { d.oldSize++; } }
			}
			List<String> gone = new ArrayList<>(), fresh = new ArrayList<>();
			for (String k : A.keySet()) { if (!B.containsKey(k)) { gone.add(k); } }
			for (String k : B.keySet()) { if (!A.containsKey(k)) { fresh.add(k); } }
			Map<Cls, Cls> pairs = new LinkedHashMap<>();
			for (var e : A.entrySet()) { Cls cb = B.get(e.getKey()); if (cb != null) { pairs.put(e.getValue(), cb); } }
			if (!d.exact) {
				// Same simple name, one candidate on each side: a move. A best guess, labelled by the hop.
				Map<String, List<String>> goneBySimple = new HashMap<>(), freshBySimple = new HashMap<>();
				for (String k : gone) { goneBySimple.computeIfAbsent(simpleType(k), x -> new ArrayList<>()).add(k); }
				for (String k : fresh) { freshBySimple.computeIfAbsent(simpleType(k), x -> new ArrayList<>()).add(k); }
				for (var e : goneBySimple.entrySet()) {
					List<String> f = freshBySimple.get(e.getKey());
					if (e.getValue().size() == 1 && f != null && f.size() == 1) {
						pairs.put(A.get(e.getValue().get(0)), B.get(f.get(0)));
						gone.remove(e.getValue().get(0));
						fresh.remove(f.get(0));
					}
				}
				// A renamed class keeps most of its member names (GuiGraphics -> GuiGraphicsExtractor):
				// pair a removed and an added class when each is the other's best match and at least
				// half of their member names are shared. Also a best guess.
				Map<String, Set<String>> goneNames = new HashMap<>(), freshNames = new HashMap<>();
				for (String k : gone) { goneNames.put(k, memberNames(A.get(k))); }
				for (String k : fresh) { freshNames.put(k, memberNames(B.get(k))); }
				Map<String, String> best = new HashMap<>(), bestBack = new HashMap<>();
				Map<String, Double> score = new HashMap<>(), scoreBack = new HashMap<>();
				for (String g : gone) {
					Set<String> gn = goneNames.get(g);
					if (gn.size() < 4) { continue; }
					for (String f : fresh) {
						Set<String> fn = freshNames.get(f);
						if (fn.size() < 4) { continue; }
						int common = 0;
						for (String n : gn) { if (fn.contains(n)) { common++; } }
						double j = (double) common / (gn.size() + fn.size() - common);
						if (j > score.getOrDefault(g, 0.0)) { score.put(g, j); best.put(g, f); }
						if (j > scoreBack.getOrDefault(f, 0.0)) { scoreBack.put(f, j); bestBack.put(f, g); }
					}
				}
				for (var e : best.entrySet()) {
					if (score.get(e.getKey()) >= 0.5 && e.getKey().equals(bestBack.get(e.getValue()))) {
						pairs.put(A.get(e.getKey()), B.get(e.getValue()));
						gone.remove(e.getKey());
						fresh.remove(e.getValue());
					}
				}
				// Nested classes that moved together name their outer class's move (GuiGraphics$ScissorStack ->
				// GuiGraphicsExtractor$ScissorStack says GuiGraphics -> GuiGraphicsExtractor), when that is the only vote.
				Map<String, Set<String>> votes = new HashMap<>();
				for (var e : new ArrayList<>(pairs.entrySet())) {
					String o = e.getKey().named, n = e.getValue().named;
					int io = o.lastIndexOf('$'), in = n.lastIndexOf('$');
					if (io < 0 || in < 0) { continue; }
					String oo = o.substring(0, io), no = n.substring(0, in);
					if (gone.contains(oo) && fresh.contains(no)) { votes.computeIfAbsent(oo, x -> new HashSet<>()).add(no); }
				}
				Map<String, Long> claimed = new HashMap<>();
				for (Set<String> v : votes.values()) { if (v.size() == 1) { claimed.merge(v.iterator().next(), 1L, Long::sum); } }
				for (var e : votes.entrySet()) {
					if (e.getValue().size() != 1) { continue; }
					String no = e.getValue().iterator().next();
					if (claimed.get(no) != 1 || !fresh.contains(no)) { continue; }
					pairs.put(A.get(e.getKey()), B.get(no));
					gone.remove(e.getKey());
					fresh.remove(no);
				}
			}
			Map<String, String> classMap = new HashMap<>(); // old official name -> new, for descriptor comparison by name
			for (var e : pairs.entrySet()) {
				if (!e.getKey().named.equals(e.getValue().named)) {
					d.classesRenamed.add(new String[] { dots(e.getKey().named), dots(e.getValue().named) });
					d.count("classesRenamed", (e.getKey().flags & ACC_PRIVATE) == 0);
					d.broken++;
					classMap.put(e.getKey().named, e.getValue().named);
				}
			}
			for (String k : gone) {
				Cls c = A.get(k);
				d.classesRemoved.add(dots(c.named));
				d.count("classesRemoved", true);
				d.broken++;
				for (Mem m : c.methods.values()) { if (!m.isPrivate()) { d.broken++; } }
				for (Mem m : c.fields.values()) { if (!m.isPrivate()) { d.broken++; } }
			}
			for (String k : fresh) { d.classesAdded.add(dots(B.get(k).named)); d.count("classesAdded", true); }
			for (var e : pairs.entrySet()) {
				Cls ca = e.getKey(), cb = e.getValue();
				d.members(ca, cb, ca.methods, cb.methods, true, classMap);
				d.members(ca, cb, ca.fields, cb.fields, false, classMap);
				if (classMap.containsKey(ca.named) && d.owners.containsKey(dots(ca.named))) { d.owners.get(dots(ca.named)).renamedTo = dots(cb.named); }
			}
			Collections.sort(d.classesAdded);
			Collections.sort(d.classesRemoved);
			d.classesRenamed.sort(Comparator.comparing(x -> x[0]));
			return d;
		}

		void members(Cls ca, Cls cb, Map<String, Mem> ma, Map<String, Mem> mb, boolean method, Map<String, String> classMap) {
			String kind = method ? "methods" : "fields", owner = dots(ca.named);
			// By-name hops key members by name (+ descriptor for methods); exact hops by intermediary id.
			Map<String, Mem> A = ma, B = mb;
			if (!exact) {
				A = new LinkedHashMap<>(); B = new LinkedHashMap<>();
				for (Mem m : ma.values()) { A.put(method ? m.name() + remap(m.desc(), classMap) : m.name(), m); }
				for (Mem m : mb.values()) { B.put(method ? m.name() + m.desc() : m.name(), m); }
			}
			List<Mem> gone = new ArrayList<>(), fresh = new ArrayList<>();
			for (var e : A.entrySet()) {
				Mem x = e.getValue(), y = B.get(e.getKey());
				if (y == null) { gone.add(x); continue; }
				boolean renamed = !x.name().equals(y.name());
				boolean changed = exact ? !x.cmpDesc().equals(y.cmpDesc()) : !remap(x.desc(), classMap).equals(y.desc());
				boolean api = !x.isPrivate();
				if (renamed && changed) {
					owner(owner).add(kind + "Renamed", sig(x, method) + " -> " + sig(y, method));
					count(kind + "Renamed", api); count(kind + "Changed", api);
				} else if (renamed) {
					owner(owner).add(kind + "Renamed", sig(x, method) + " -> " + sig(y, method));
					count(kind + "Renamed", api);
				} else if (changed) {
					owner(owner).add(kind + "Changed", sig(x, method) + " -> " + sig(y, method));
					count(kind + "Changed", api);
				}
				if ((renamed || changed) && api) { broken++; }
			}
			for (var e : B.entrySet()) { if (!A.containsKey(e.getKey())) { fresh.add(e.getValue()); } }
			// Same name, one on each side: the signature changed (constructors, and every by-name hop).
			Map<String, List<Mem>> goneByName = group(gone, Mem::name), freshByName = group(fresh, Mem::name);
			for (var e : goneByName.entrySet()) {
				List<Mem> f = freshByName.get(e.getKey());
				if (e.getValue().size() == 1 && f != null && f.size() == 1) {
					Mem x = e.getValue().get(0), y = f.get(0);
					owner(owner).add(kind + "Changed", sig(x, method) + " -> " + sig(y, method));
					count(kind + "Changed", !x.isPrivate());
					if (!x.isPrivate()) { broken++; }
					gone.remove(x); fresh.remove(y);
				}
			}
			if (!exact) {
				// Same descriptor, one on each side, in the same class: a rename, best guess.
				Map<String, List<Mem>> goneByDesc = group(gone, m -> remap(m.desc(), classMap)), freshByDesc = group(fresh, Mem::desc);
				for (var e : goneByDesc.entrySet()) {
					List<Mem> f = freshByDesc.get(e.getKey());
					if (e.getValue().size() == 1 && f != null && f.size() == 1 && !e.getKey().equals("()V")) {
						Mem x = e.getValue().get(0), y = f.get(0);
						owner(owner).add(kind + "Renamed", sig(x, method) + " -> " + sig(y, method));
						count(kind + "Renamed", !x.isPrivate());
						if (!x.isPrivate()) { broken++; }
						gone.remove(x); fresh.remove(y);
					}
				}
			}
			for (Mem x : gone) { owner(owner).add(kind + "Removed", sig(x, method)); count(kind + "Removed", !x.isPrivate()); if (!x.isPrivate()) { broken++; } }
			for (Mem y : fresh) { owner(owner).add(kind + "Added", sig(y, method)); count(kind + "Added", !y.isPrivate()); }
		}

		static Set<String> memberNames(Cls c) {
			Set<String> s = new HashSet<>();
			for (Mem m : c.methods.values()) { if (!m.name().equals("<init>")) { s.add("m" + m.name()); } }
			for (Mem m : c.fields.values()) { s.add("f" + m.name()); }
			return s;
		}

		static Map<String, List<Mem>> group(List<Mem> l, java.util.function.Function<Mem, String> f) {
			Map<String, List<Mem>> m = new LinkedHashMap<>();
			for (Mem x : l) { m.computeIfAbsent(f.apply(x), k -> new ArrayList<>()).add(x); }
			return m;
		}

		static String remap(String desc, Map<String, String> classMap) {
			return classMap.isEmpty() ? desc : VersionMap.Tiny.remapDesc(desc, classMap);
		}

		String json() {
			StringBuilder sb = new StringBuilder("{\n");
			sb.append("  \"from\": ").append(q(from)).append(", \"to\": ").append(q(to)).append(",\n");
			sb.append("  \"names\": \"official\",\n");
			sb.append("  \"linked\": ").append(q(exact ? "exact" : "by-name")).append(",\n");
			sb.append("  \"oldSize\": ").append(oldSize).append(", \"broken\": ").append(broken)
					.append(", \"breakingShare\": ").append(String.format(Locale.ROOT, "%.4f", breakingShare())).append(",\n");
			sb.append("  \"counts\": {");
			int i = 0;
			for (var e : counts.entrySet()) { sb.append(i++ == 0 ? "" : ", ").append(q(e.getKey())).append(": ").append(e.getValue()); }
			sb.append("},\n");
			sb.append("  \"classesAdded\": [").append(String.join(", ", classesAdded.stream().map(ChangeIndex::q).toList())).append("],\n");
			sb.append("  \"classesRemoved\": [").append(String.join(", ", classesRemoved.stream().map(ChangeIndex::q).toList())).append("],\n");
			sb.append("  \"classesRenamed\": [").append(String.join(", ", classesRenamed.stream().map(x -> "[" + q(x[0]) + ", " + q(x[1]) + "]").toList())).append("],\n");
			sb.append("  \"members\": {");
			i = 0;
			for (OwnerChanges o : owners.values()) {
				sb.append(i++ == 0 ? "\n    " : ",\n    ").append(q(o.owner)).append(": {");
				int j = 0;
				if (o.renamedTo != null) { sb.append("\"to\": ").append(q(o.renamedTo)); j++; }
				for (var e : o.lists.entrySet()) {
					sb.append(j++ == 0 ? "" : ", ").append(q(e.getKey())).append(": [").append(String.join(", ", e.getValue().stream().map(ChangeIndex::q).toList())).append("]");
				}
				sb.append("}");
			}
			sb.append("\n  }\n}\n");
			return sb.toString();
		}
	}
}
