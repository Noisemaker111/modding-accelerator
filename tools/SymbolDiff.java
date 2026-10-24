import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * SymbolDiff - "what broke after the game update".
 *
 * Indexes the NEW Minecraft jar, walks a mod's compiled classes, and reports every symbolic
 * reference (method, field, class) the new jar no longer provides, each with a nearest-candidate
 * suggestion from the new jar so a renamed vanilla method points at its replacement.
 *
 * Single file, no dependencies, JDK 25 ClassFile API:
 *   java tools/SymbolDiff.java &lt;oldJar&gt; &lt;newJar&gt; &lt;modClassesDir&gt; [--json]
 *
 * Exit: 0 nothing missing, 1 something missing, 2 usage or I/O error.
 */
public final class SymbolDiff {
	private static final String USAGE =
			"usage: java tools/SymbolDiff.java <oldJar> <newJar> <modClassesDir> [--json]";

	/** Library noise. Other non-net/minecraft references are dropped too, unless the OLD jar has them. */
	private static final List<String> IGNORED =
			List.of("java/", "javax/", "jdk/", "org/", "com/mojang/", "net/fabricmc/");

	/** A missing member, attributed to the mod class that references it. */
	private record Miss(String modClass, String owner, String name, String descriptor, String kind,
			List<String> candidates) {
	}

	/** A missing class referenced by a mod class. */
	private record ClassMiss(String modClass, String referenced, List<String> candidates) {
	}

	/** Jar index of declared members only: owner -> set of "name descriptor". */
	private static final class Index {
		final Set<String> classes = new HashSet<>();
		final Map<String, Set<String>> methods = new HashMap<>();
		final Map<String, Set<String>> fields = new HashMap<>();
		final Map<String, List<String>> supers = new HashMap<>();

		Map<String, Set<String>> table(boolean method) {
			return method ? methods : fields;
		}
	}
	public static void main(String[] args) {
		boolean json = false;
		List<String> files = new ArrayList<>();
		for (String arg : args) {
			if ("--json".equals(arg)) {
				json = true;
			} else if (arg.startsWith("-")) {
				System.err.println(USAGE);
				System.exit(2);
			} else {
				files.add(arg);
			}
		}
		if (files.size() != 3) {
			System.err.println(USAGE);
			System.exit(2);
		}

		var misses = new ArrayList<Miss>();
		var classMisses = new ArrayList<ClassMiss>();
		var counts = new LinkedHashMap<String, Integer>();
		try {
			scan(files.get(0), files.get(1), files.get(2), misses, classMisses, counts);
		} catch (IOException e) {
			System.err.println("error: " + e.getMessage() + "\n" + USAGE);
			System.exit(2);
		}
		System.out.println(json ? toJson(misses, classMisses, counts)
				: report(misses, classMisses, counts));
		System.exit(misses.isEmpty() && classMisses.isEmpty() ? 0 : 1);
	}

	// ------------------------------------------------------------------ scanning

	private static void scan(String oldJar, String newJar, String modDir, List<Miss> misses,
			List<ClassMiss> classMisses, Map<String, Integer> counts) throws IOException {
		Path newPath = require(newJar, false);
		Path oldPath = require(oldJar, false);
		Path dir = require(modDir, true);
		Index fresh = indexJar(newPath);
		// The old jar only gates non-net/minecraft references, so entry names are enough there.
		Set<String> oldClasses = jarClassNames(oldPath);

		List<Path> modFiles;
		try (var walk = Files.walk(dir)) {
			modFiles = walk.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p))
					.sorted().toList();
		}

		var seen = new HashMap<String, Set<String>>(); // dedupe refs and class refs per mod class
		int methodRefs = 0;
		int fieldRefs = 0;
		int classRefs = 0;

		for (Path file : modFiles) {
			ClassModel model;
			try {
				model = ClassFile.of().parse(Files.readAllBytes(file));
			} catch (IOException | RuntimeException e) {
				System.err.println("skip unreadable class " + file + ": " + e);
				continue;
			}
			String modClass = model.thisClass().asInternalName();
			Set<String> seenKeys = seen.computeIfAbsent(modClass, k -> new HashSet<>());
			Set<String> types = new LinkedHashSet<>();
			var refs = new ArrayList<String[]>(); // {kind, owner, name, descriptor}

			// Every method ref, field ref and class constant the class file uses is a pool entry,
			// lambda handles included (their Methodref entries exist independently of the bootstrap).
			for (PoolEntry entry : model.constantPool()) {
				if (entry instanceof MemberRefEntry ref) {
					// Field refs, method refs and interface method refs all share this shape.
					refs.add(new String[] {ref instanceof FieldRefEntry ? "field" : "method",
							ref.owner().asInternalName(), ref.name().stringValue(),
							ref.type().stringValue()});
				} else if (entry instanceof ClassEntry c) {
					types.add(c.asInternalName());
				}
			}

			for (String[] ref : refs) {
				String owner = ref[1];
				String name = ref[2];
				String descriptor = ref[3];
				// Types named inside a descriptor are references too, so a removed type is reported
				// even when it only appears in a signature.
				types.addAll(descriptorClasses(descriptor));
				if (!kept(owner, oldClasses)) {
					continue;
				}
				if ("method".equals(ref[0])) {
					methodRefs++;
				} else {
					fieldRefs++;
				}
				if (!seenKeys.add("M " + ref[0] + " " + owner + " " + name + " " + descriptor)) {
					continue; // same reference twice in one class file
				}
				if (!fresh.classes.contains(owner)) {
					continue; // an absent class is reported once, not once per member
				}
				// The index holds declared members, but a call may legally resolve to an inherited
				// one, so resolution walks the owner's supertypes.
				Set<String> visible = visibleMembers(fresh, owner, "method".equals(ref[0]));
				if (visible.contains(name + " " + descriptor)) {
					continue; // still there, nothing broke
				}
				misses.add(new Miss(modClass, owner, name, descriptor, ref[0],
						suggestMember(visible, name, descriptor)));
			}

			for (String type : types) {
				if (!kept(type, oldClasses) || !seenKeys.add("C " + type)) {
					continue;
				}
				classRefs++;
				if (!fresh.classes.contains(type)) {
					classMisses.add(new ClassMiss(modClass, type, suggestClass(fresh.classes, type)));
				}
			}
		}

		counts.put("modClasses", modFiles.size());
		counts.put("newJarClasses", fresh.classes.size());
		counts.put("methodReferences", methodRefs);
		counts.put("fieldReferences", fieldRefs);
		counts.put("classReferences", classRefs);
		counts.put("missingMembers", misses.size());
		counts.put("missingClasses", classMisses.size());
	}

	/** Resolve an argument to a real path, or fail with a message that names it. */
	private static Path require(String text, boolean directory) throws IOException {
		Path path = Path.of(text);
		if (!(directory ? Files.isDirectory(path) : Files.isRegularFile(path))) {
			throw new IOException((directory ? "not a directory: " : "not a file: ") + text);
		}
		return path;
	}

	/** Worth reporting: net/minecraft, or a class the old jar knew about. */
	private static boolean kept(String internalName, Set<String> oldClasses) {		for (String prefix : IGNORED) {
			if (internalName.startsWith(prefix)) {
				return false;
			}
		}
		return internalName.startsWith("net/minecraft/") || oldClasses.contains(internalName);
	}

	/** Declared members plus inherited ones, so supertype calls are not false alarms. */
	private static Set<String> visibleMembers(Index index, String owner, boolean method) {
		Map<String, Set<String>> table = index.table(method);
		Set<String> out = new HashSet<>();
		Set<String> visited = new HashSet<>();
		ArrayDeque<String> pending = new ArrayDeque<>(List.of(owner));
		while (!pending.isEmpty()) {
			String current = pending.poll();
			if (!visited.add(current)) {
				continue;
			}
			out.addAll(table.getOrDefault(current, Set.of()));
			pending.addAll(index.supers.getOrDefault(current, List.of()));
		}
		return out;
	}

	/** Parse every class in the jar and record its declared members and supertypes. */
	private static Index indexJar(Path jar) throws IOException {
		Index index = new Index();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				String entryName = entry.getName();
				if (entry.isDirectory() || !entryName.endsWith(".class")
						|| entryName.startsWith("META-INF/")) {
					continue;
				}
				ClassModel model;
				try (InputStream in = zip.getInputStream(entry)) {
					model = ClassFile.of().parse(in.readAllBytes());
				} catch (RuntimeException e) {
					continue; // class format this JDK cannot read
				}
				String owner = model.thisClass().asInternalName();
				index.classes.add(owner);
				Set<String> methods = index.methods.computeIfAbsent(owner, k -> new HashSet<>());
				Set<String> fields = index.fields.computeIfAbsent(owner, k -> new HashSet<>());
				model.methods().forEach(m -> methods.add(
						m.methodName().stringValue() + " " + m.methodType().stringValue()));
				model.fields().forEach(f -> fields.add(
						f.fieldName().stringValue() + " " + f.fieldType().stringValue()));
				List<String> supers = new ArrayList<>();
				model.superclass().ifPresent(s -> supers.add(s.asInternalName()));
				model.interfaces().forEach(i -> supers.add(i.asInternalName()));
				index.supers.put(owner, supers);
			}
		}
		return index;
	}

	/** Class names straight from jar entry names; cheap and enough for a whitelist. */
	private static Set<String> jarClassNames(Path jar) throws IOException {
		Set<String> names = new HashSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				String name = entry.getName();
				if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
					names.add(name.substring(0, name.length() - ".class".length()));
				}
			}
		}
		return names;
	}

	/** Object types mentioned by a method or field descriptor. */
	private static Set<String> descriptorClasses(String descriptor) {
		Set<String> types = new LinkedHashSet<>();
		for (int i = 0; i < descriptor.length(); i++) {
			if (descriptor.charAt(i) != 'L') {
				continue;
			}
			int end = descriptor.indexOf(';', i);
			if (end < 0) {
				break;
			}
			types.add(descriptor.substring(i + 1, end));
			i = end;
		}
		return types;
	}

	// --------------------------------------------------------------- suggestions

	/**
	 * Nearest visible member of the same owner. Same-name members rank by descriptor distance, so
	 * a changed signature still names the right method; a vanished name falls back to close names.
	 */
	private static List<String> suggestMember(Set<String> members, String name, String descriptor) {
		Map<String, Double> scores = new HashMap<>();
		for (String member : members) {
			int space = member.lastIndexOf(' ');
			String memberName = member.substring(0, space);
			String memberDesc = member.substring(space + 1);
			double score = memberName.equals(name) ? 1.0 + similarity(memberDesc, descriptor)
					: similarity(memberName, name);
			if (score >= 0.72) {
				scores.put(memberName + memberDesc, score);
			}
		}
		return top(scores, 3);
	}

	/**
	 * Nearest existing class name. Same-package candidates win because a move inside one package
	 * is the common breakage; same-depth names are only used when that finds nothing.
	 */
	private static List<String> suggestClass(Set<String> classes, String missing) {
		int slash = missing.lastIndexOf('/');
		String pkg = slash < 0 ? "" : missing.substring(0, slash + 1);
		Map<String, Double> samePackage = new HashMap<>();
		Map<String, Double> sameDepth = new HashMap<>();
		for (String candidate : classes) {
			// Length guard keeps the Levenshtein sweep cheap across thousands of class names.
			if (candidate.lastIndexOf('/') != slash
					|| Math.abs(candidate.length() - missing.length()) > 12) {
				continue;
			}
			double score = similarity(candidate, missing);
			if (score >= 0.6) {
				(candidate.startsWith(pkg) ? samePackage : sameDepth).put(candidate, score);
			}
		}
		List<String> out = top(samePackage, 2);
		return out.isEmpty() ? top(sameDepth, 2) : out;
	}

	private static List<String> top(Map<String, Double> scores, int limit) {
		return scores.entrySet().stream()
				.sorted(Map.Entry.<String, Double>comparingByValue().reversed()
						.thenComparing(Map.Entry.comparingByKey()))
				.limit(limit).map(Map.Entry::getKey).toList();
	}

	/** Normalized Levenshtein similarity in [0,1]; 1 means identical. */
	static double similarity(String a, String b) {
		if (a.equals(b)) {
			return 1.0;
		}
		int max = Math.max(a.length(), b.length());
		if (max == 0) {
			return 1.0;
		}
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
						previous[j - 1] + cost);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return 1.0 - ((double) previous[b.length()] / max);
	}

	// --------------------------------------------------------------------- output

	private static String report(List<Miss> misses, List<ClassMiss> classMisses,
			Map<String, Integer> counts) {
		Map<String, List<Miss>> byMember = new TreeMap<>();
		Map<String, List<ClassMiss>> byType = new TreeMap<>();
		misses.forEach(m -> byMember.computeIfAbsent(m.modClass(), k -> new ArrayList<>()).add(m));
		classMisses.forEach(m -> byType.computeIfAbsent(m.modClass(), k -> new ArrayList<>()).add(m));
		Set<String> modClasses = new TreeSet<>(byMember.keySet());
		modClasses.addAll(byType.keySet());

		StringBuilder out = new StringBuilder();
		for (String modClass : modClasses) {
			// One block per mod class, so a reader sees one file's breakage at a time.
			out.append(modClass).append(".java\n");
			byMember.getOrDefault(modClass, List.of()).stream()
					.sorted(Comparator.comparing(Miss::owner).thenComparing(Miss::name)
							.thenComparing(Miss::descriptor))
					.forEach(m -> out.append("  ").append(m.owner()).append('.').append(m.name())
							.append(m.descriptor()).append(hint(m.owner(), m.candidates()))
							.append('\n'));
			byType.getOrDefault(modClass, List.of()).stream()
					.sorted(Comparator.comparing(ClassMiss::referenced))
					.forEach(m -> out.append("  ").append(m.referenced()).append("  (class)")
							.append(hint("", m.candidates())).append('\n'));
			out.append('\n');
		}

		boolean clean = counts.get("missingMembers") == 0 && counts.get("missingClasses") == 0;
		out.append(clean ? "OK: no broken references"
				: "BROKEN: " + counts.get("missingMembers") + " missing member(s), "
						+ counts.get("missingClasses") + " missing class(es)");
		return out.append("  [").append(counts.get("modClasses")).append(" mod class(es) against ")
				.append(counts.get("newJarClasses")).append(" new-jar class(es); ")
				.append(counts.get("methodReferences")).append(" method refs, ")
				.append(counts.get("fieldReferences")).append(" field refs, ")
				.append(counts.get("classReferences")).append(" class refs]").toString();
	}

	private static String hint(String owner, List<String> candidates) {
		if (candidates.isEmpty()) {
			return "";
		}
		String prefix = owner.isEmpty() ? "" : owner + ".";
		StringBuilder out = new StringBuilder("  -> did you mean ").append(prefix)
				.append(candidates.get(0)).append('?');
		if (candidates.size() > 1) {
			out.append("  (also ").append(prefix)
					.append(String.join(", " + prefix, candidates.subList(1, candidates.size())))
					.append(')');
		}
		return out.toString();
	}

	private static String toJson(List<Miss> misses, List<ClassMiss> classMisses,
			Map<String, Integer> counts) {
		StringBuilder out = new StringBuilder("{\n  \"missing\": [");
		for (int i = 0; i < misses.size(); i++) {
			Miss miss = misses.get(i);
			out.append(i == 0 ? "\n" : ",\n")
					.append("    {\"class\": ").append(json(miss.modClass()))
					.append(", \"owner\": ").append(json(miss.owner()))
					.append(", \"name\": ").append(json(miss.name()))
					.append(", \"descriptor\": ").append(json(miss.descriptor()))
					.append(", \"kind\": ").append(json(miss.kind()))
					.append(", \"candidates\": ").append(jsonArray(miss.candidates())).append('}');
		}
		out.append(misses.isEmpty() ? "]" : "\n  ]").append(",\n  \"classes\": [");
		for (int i = 0; i < classMisses.size(); i++) {
			ClassMiss miss = classMisses.get(i);
			out.append(i == 0 ? "\n" : ",\n")
					.append("    {\"class\": ").append(json(miss.modClass()))
					.append(", \"referenced\": ").append(json(miss.referenced()))
					.append(", \"candidates\": ").append(jsonArray(miss.candidates())).append('}');
		}
		out.append(classMisses.isEmpty() ? "]" : "\n  ]").append(",\n  \"counts\": {");
		int n = 0;
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			out.append(n++ > 0 ? ", " : "").append(json(entry.getKey())).append(": ")
					.append(entry.getValue());
		}
		return out.append("}\n}").toString();
	}

	private static String jsonArray(List<String> values) {
		return "[" + String.join(", ", values.stream().map(SymbolDiff::json).toList()) + "]";
	}

	private static String json(String value) {
		StringBuilder out = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : c);
			}
		}
		return out.append('"').toString();
	}
}
