import java.io.*;
import java.lang.classfile.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * ApiDiff - the Minecraft API difference between two versions, from the jars alone.
 *
 * SymbolDiff answers "what does this mod use that the new jar no longer has". This answers the
 * earlier question: what changed in the game's own API between an old jar and a new one, so a port
 * cause is known before any mod build fails.
 *
 * Both jars must use the SAME mapping family (yarn+yarn or mojmap+mojmap), otherwise every name
 * differs and the result is noise. The run reports the overlap ratio so a mismatch is visible.
 *
 * A rename is linked, not guessed: an obfuscated/intermediary name survives a human rename, so
 * old jar -> old mappings -> stable id -> new mappings -> new jar names the replacement exactly.
 * When no mapping file describes a jar, the fallback links (same simple name, or the only new
 * member with the old descriptor) are labelled "heuristic".
 *
 * Single file, no dependencies, JDK 25 ClassFile API:
 *   java tools/ApiDiff.java <oldJar> <newJar> [--out <json>]
 *                          [--old-mappings <tiny>] [--new-mappings <tiny>] [--no-links]
 *
 * Mapping files are found automatically beside the jars or in the loom cache when the jar path
 * carries a "layered+hash.<n>" id; --old-mappings/--new-mappings override that.
 *
 * Exit: 0 diff produced, 2 usage or I/O error.
 */
public final class ApiDiff {
	private static final String USAGE = "usage: java tools/ApiDiff.java <oldJar> <newJar> [--out <json>]"
			+ " [--old-mappings <tiny>] [--new-mappings <tiny>] [--no-links]";

	/** A declared member: owner, name and descriptor. */
	private record Member(String owner, String name, String descriptor, boolean method) {
	}

	/** A signature change: same name, a different descriptor in the new jar. */
	private record Signature(String owner, String name, String kind, String oldDescriptor,
			String newDescriptor) {
	}

	/** A class that kept its simple name and changed package. */
	private record Move(String from, String to, String simple, String confidence) {
	}

	/**
	 * A removed symbol that reappears in the new jar under another name. "evidence" is "stable-id"
	 * when an intermediary id proves it and "heuristic" when the fallback rules found it.
	 */
	private record Replacement(String from, String to, String kind, String stableId, String descriptor,
			String newDescriptor, String evidence, String match) {
	}

	/** Per-class tallies, kept as mutable counters while scanning. */
	private static final class Tally {
		int methodsRemoved;
		int fieldsRemoved;
		int signatures;
		String status = "changed";
	}

	/** Declared members of one jar. */
	private static final class Index {
		final Set<String> classes = new LinkedHashSet<>();
		final Map<String, List<String[]>> methods = new HashMap<>(); // owner -> {name, descriptor}
		final Map<String, List<String[]>> fields = new HashMap<>();
		int skipped;
		int entries;
	}

	/**
	 * One side of the stable-id mapping: either human -> intermediary (old jar) or intermediary ->
	 * human (new jar).
	 */
	private static final class Mappings {
		final Path file;
		/** class forward: human -> id, or backward: id -> human. */
		final Map<String, String> classes = new HashMap<>();
		/** Member maps keyed by owner + '\u0000' + kind ('m'/'f') + name. */
		final Map<String, String> members = new HashMap<>();
		/** Human keys that more than one declaration maps to, so the id would be a guess. */
		final Set<String> ambiguous = new HashSet<>();
		final boolean idToHuman;
		double classAgreement;
		String cleared; // why the mapping cannot be trusted, or null

		Mappings(Path file, boolean idToHuman) {
			this.file = file;
			this.idToHuman = idToHuman;
		}
	}

	/** Everything the link step produced. */
	private static final class Links {
		final List<Replacement> all = new ArrayList<>();
		Mappings old;
		Mappings recent;
		String oldNote;
		String recentNote;
		int unlinkedClasses;
		int unlinkedMembers;
	}

	public static void main(String[] args) {
		Path out = null;
		String oldMappings = null;
		String newMappings = null;
		boolean noLinks = false;
		List<String> files = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if ("--out".equals(arg)) {
				if (++i >= args.length) {
					System.err.println(USAGE);
					System.exit(2);
				}
				out = Path.of(args[i]);
			} else if ("--old-mappings".equals(arg)) {
				if (++i >= args.length) {
					System.err.println(USAGE);
					System.exit(2);
				}
				oldMappings = args[i];
			} else if ("--new-mappings".equals(arg)) {
				if (++i >= args.length) {
					System.err.println(USAGE);
					System.exit(2);
				}
				newMappings = args[i];
			} else if ("--no-links".equals(arg)) {
				noLinks = true;
			} else if (arg.startsWith("-")) {
				System.err.println(USAGE);
				System.exit(2);
			} else {
				files.add(arg);
			}
		}
		if (files.size() != 2) {
			System.err.println(USAGE);
			System.exit(2);
		}

		String json;
		try {
			Path oldJar = require(files.get(0));
			Path newJar = require(files.get(1));
			Index before = indexJar(oldJar);
			Index after = indexJar(newJar);
			Links links = noLinks ? null : link(oldJar, newJar, before, after, oldMappings, newMappings);
			if (links == null) {
				links = new Links();
			}
			json = diff(oldJar, newJar, before, after, links);
		} catch (IOException e) {
			System.err.println("error: " + e.getMessage() + "\n" + USAGE);
			System.exit(2);
			return;
		}
		if (out == null) {
			System.out.println(json);
			return;
		}
		try {
			Path parent = out.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(out, json + System.lineSeparator(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.err.println("error: cannot write " + out + ": " + e.getMessage());
			System.exit(2);
			return;
		}
		System.out.println("api diff written to " + out.toAbsolutePath());
	}

	private static Path require(String text) throws IOException {
		Path path = Path.of(text);
		if (!Files.isRegularFile(path)) {
			throw new IOException("not a file: " + text);
		}
		return path;
	}

	// ------------------------------------------------------------------ scanning

	/** Parse every class in the jar and record its declared members. */
	private static Index indexJar(Path jar) throws IOException {
		Index index = new Index();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				String entryName = entry.getName();
				index.entries++;
				if (entry.isDirectory() || !entryName.endsWith(".class")
						|| entryName.startsWith("META-INF/")) {
					continue;
				}
				ClassModel model;
				try (InputStream in = zip.getInputStream(entry)) {
					model = ClassFile.of().parse(in.readAllBytes());
				} catch (RuntimeException e) {
					index.skipped++; // class format this JDK cannot read
					continue;
				}
				String owner = model.thisClass().asInternalName();
				index.classes.add(owner);
				List<String[]> ms = new ArrayList<>();
				for (MethodModel m : model.methods()) {
					ms.add(new String[] {m.methodName().stringValue(), m.methodType().stringValue()});
				}
				List<String[]> fs = new ArrayList<>();
				for (FieldModel f : model.fields()) {
					fs.add(new String[] {f.fieldName().stringValue(), f.fieldType().stringValue()});
				}
				index.methods.put(owner, ms);
				index.fields.put(owner, fs);
			}
		}
		return index;
	}

	// ------------------------------------------------------------------ stable ids

	/**
	 * Link removed symbols to the new names that replaced them.
	 *
	 * The old side turns a human name into its intermediary id; the new side turns the same
	 * intermediary id back into a human name. A different human name for the same id is a rename
	 * that cannot be a guess. Both mapping files are read only in the direction they are needed.
	 */
	private static Links link(Path oldJar, Path newJar, Index before, Index after, String oldMapArg,
			String newMapArg) {
		Links links = new Links();
		Mappings old = readMappings(findMappings(oldJar, oldMapArg), false);
		Mappings recent = readMappings(findMappings(newJar, newMapArg), true);
		links.old = old;
		links.recent = recent;
		if (old == null) {
			links.oldNote = "no mapping file found for " + oldJar.getFileName()
					+ ": renames can only be guessed, so links below are labelled heuristic";
		}
		if (recent == null) {
			links.recentNote = "no mapping file found for " + newJar.getFileName()
					+ ": renames can only be guessed, so links below are labelled heuristic";
		}

		// -- classes: removed -> stable id -> new human name
		Map<String, String> classTo = new LinkedHashMap<>();
		if (usable(old, before) && usable(recent, after)) {
			old.classAgreement = agreement(old, before);
			recent.classAgreement = agreement(recent, after);
			if (old.classAgreement < 0.6) {
				old.cleared = String.format(
						"its names match only %.0f%% of the old jar's classes, so it does not describe that jar",
						old.classAgreement * 100);
			}
			if (recent.classAgreement < 0.6) {
				recent.cleared = String.format(
						"its names match only %.0f%% of the new jar's classes, so it does not describe that jar",
						recent.classAgreement * 100);
			}
		}
		boolean stable = usable(old, before) && usable(recent, after);

		Set<String> removed = new TreeSet<>(before.classes);
		removed.removeAll(after.classes);
		Set<String> added = new TreeSet<>(after.classes);
		added.removeAll(before.classes);
		Set<String> linkedTo = new HashSet<>();
		Set<String> linkedFrom = new HashSet<>();

		if (stable) {
			for (String from : removed) {
				String id = old.classes.get(from);
				if (id == null) {
					continue;
				}
				String to = recent.classes.get(id);
				if (to == null || to.equals(from) || !after.classes.contains(to) || linkedTo.contains(to)) {
					continue;
				}
				links.all.add(new Replacement(from, to, "class", id, null, null, "stable-id",
						"intermediary"));
				classTo.put(from, to);
				linkedFrom.add(from);
				linkedTo.add(to);
			}
		}

		// -- members: owner id + member id -> new human name
		List<Member> membersRemoved = removedMembers(before, after);
		Set<String> linkedMembers = new HashSet<>();
		if (stable) {
			for (Member member : membersRemoved) {
				String kind = member.method() ? "m" : "f";
				String ownerId = old.classes.get(member.owner());
				if (ownerId == null) {
					continue;
				}
				String memberId = old.members.get(key(member.owner(), kind, member.name()));
				if (memberId == null) {
					continue;
				}
				String newOwner = recent.classes.getOrDefault(ownerId, member.owner());
				String newName = recent.members.get(key(ownerId, kind, memberId));
				if (newName == null || newName.equals(member.name())) {
					continue;
				}
				String[] found = find(after, newOwner, newName, member.method());
				if (found == null) {
					continue;
				}
				String prefix = member.owner() + ".";
				String newPrefix = newOwner + ".";
				links.all.add(new Replacement(prefix + member.name(), newPrefix + newName,
						member.method() ? "method" : "field", memberId, member.descriptor(), found[1],
						"stable-id", "intermediary"));
				linkedMembers.add(memberKey(member));
			}
		}

		// -- fallbacks, only where nothing proved the link
		Map<String, List<String[]>> freshMembers = new HashMap<>(); // owner|kind -> new-only members
		for (String owner : before.classes) {
			if (!after.classes.contains(owner)) {
				continue;
			}
			for (boolean method : new boolean[] {true, false}) {
				Map<String, List<String[]>> oldTable = method ? before.methods : before.fields;
				Map<String, List<String[]>> newTable = method ? after.methods : after.fields;
				Set<String> oldNames = new HashSet<>();
				for (String[] m : oldTable.getOrDefault(owner, List.of())) {
					oldNames.add(m[0]);
				}
				List<String[]> fresh = new ArrayList<>();
				for (String[] m : newTable.getOrDefault(owner, List.of())) {
					if (!oldNames.contains(m[0])) {
						fresh.add(m);
					}
				}
				if (!fresh.isEmpty()) {
					freshMembers.put(owner + '\u0000' + (method ? "m" : "f"), fresh);
				}
			}
		}
		// A renamed class keeps its member names, so an identical member-name set is a weak hint.
		Map<String, String> uniqueAddedByFingerprint = new HashMap<>();
		Map<String, Integer> fingerprintSeen = new HashMap<>();
		for (String owner : added) {
			String fingerprint = fingerprint(after, owner);
			if (fingerprint == null) {
				continue;
			}
			int seen = fingerprintSeen.merge(fingerprint, 1, Integer::sum);
			if (seen == 1) {
				uniqueAddedByFingerprint.put(fingerprint, owner);
			} else {
				uniqueAddedByFingerprint.remove(fingerprint);
			}
		}

		for (String from : removed) {
			if (linkedFrom.contains(from) || synthetic(from)) {
				continue;
			}
			String fingerprint = fingerprint(before, from);
			if (fingerprint == null) {
				continue;
			}
			String to = uniqueAddedByFingerprint.get(fingerprint);
			if (to == null || linkedTo.contains(to)) {
				continue;
			}
			links.all.add(new Replacement(from, to, "class", null, null, null, "heuristic",
					"same-member-names"));
			linkedFrom.add(from);
			linkedTo.add(to);
		}

		Set<String> usedHeuristicTargets = new HashSet<>();

		for (Member member : membersRemoved) {
			if (linkedMembers.contains(memberKey(member))) {
				continue;
			}
			if (synthetic(member.name())) {
				continue; // a compiler artifact or an unmapped id is not a rename worth guessing
			}
			String kind = member.method() ? "m" : "f";
			List<String[]> fresh = freshMembers.getOrDefault(member.owner() + '\u0000' + kind, List.of());
			String match = null;
			int candidates = 0;
			for (String[] candidate : fresh) {
				if (candidate[1].equals(member.descriptor()) && !synthetic(candidate[0])) {
					candidates++;
					match = candidate[0];
				}
			}
			// A descriptor shared by several removals names none of them, e.g. four int constants.
			if (candidates != 1 || sharedDescriptor(membersRemoved, member)
					|| !usedHeuristicTargets.add(member.owner() + "." + match)) {
				continue;
			}
			links.all.add(new Replacement(member.owner() + "." + member.name(),
					member.owner() + "." + match, member.method() ? "method" : "field", null,
					member.descriptor(), member.descriptor(), "heuristic", "same-descriptor"));
		}

		// -- how much of the removal list stays unlinked
		Set<String> linkedClassKeys = new HashSet<>();
		Set<String> linkedMemberKeys = new HashSet<>();
		for (Replacement replacement : links.all) {
			if ("class".equals(replacement.kind())) {
				linkedClassKeys.add(replacement.from());
			} else {
				linkedMemberKeys.add(replacement.from());
			}
		}
		for (String from : removed) {
			if (!linkedClassKeys.contains(from)) {
				links.unlinkedClasses++;
			}
		}
		for (Member member : membersRemoved) {
			if (!linkedMemberKeys.contains(member.owner() + "." + member.name())) {
				links.unlinkedMembers++;
			}
		}
		return links;
	}

	private static boolean usable(Mappings mappings, Index index) {
		return mappings != null && mappings.cleared == null;
	}

	/** True when another removal in the same class and kind carries the same descriptor. */
	private static boolean sharedDescriptor(List<Member> removals, Member member) {		int seen = 0;
		for (Member other : removals) {
			if (other.method() == member.method() && other.owner().equals(member.owner())
					&& other.descriptor().equals(member.descriptor())) {
				seen++;
				if (seen > 1) {
					return true;
				}
			}
		}
		return false;
	}

	/** Share of sampled human class names that really exist in the jar this mapping describes. */
	private static double agreement(Mappings mappings, Index index) {
		Collection<String> humans = mappings.idToHuman ? mappings.classes.values() : mappings.classes.keySet();
		int sample = 0;
		int hit = 0;
		for (String name : humans) {
			if (!name.startsWith("net/minecraft/") || sample >= 500) {
				continue;
			}
			sample++;
			if (index.classes.contains(name)) {
				hit++;
			}
		}
		return sample == 0 ? 0 : hit / (double) sample;
	}

	/**
	 * Read a tiny v2 mapping file in one direction. Namespace columns are looked up by name, so
	 * yarn (official/intermediary/named) and loom layered files both work.
	 */
	private static Mappings readMappings(Path tiny, boolean idToHuman) {
		if (tiny == null) {
			return null;
		}
		Mappings mappings = new Mappings(tiny, idToHuman);
		try (BufferedReader reader = Files.newBufferedReader(tiny, StandardCharsets.UTF_8)) {
			String header = reader.readLine();
			if (header == null) {
				throw new IOException("empty mapping file");
			}
			String[] columns = header.split("\t", -1);
			int intermediary = -1;
			int named = -1;
			for (int i = 3; i < columns.length; i++) {
				if ("intermediary".equals(columns[i])) {
					intermediary = i - 3;
				}
				if ("named".equals(columns[i])) {
					named = i - 3;
				}
			}
			if (intermediary < 0 || named < 0) {
				throw new IOException("no intermediary and named namespaces in " + tiny);
			}
			int widest = Math.max(intermediary, named);
			String ownerId = null;
			String ownerName = null;
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				if (line.charAt(0) == 'c' && line.length() > 1 && line.charAt(1) == '\t') {
					String[] parts = line.split("\t", -1);
					if (parts.length < 2 + widest) {
						ownerId = null;
						ownerName = null;
						continue;
					}
					ownerId = parts[1 + intermediary];
					ownerName = parts[1 + named];
					if (!ownerId.isEmpty() && !ownerName.isEmpty()) {
						if (idToHuman) {
							mappings.classes.put(ownerId, ownerName);
						} else {
							mappings.classes.put(ownerName, ownerId);
						}
					}
					continue;
				}
				if (line.charAt(0) != '\t' || ownerId == null) {
					continue;
				}
				if (!line.startsWith("\tm\t") && !line.startsWith("\tf\t")) {
					continue; // parameter names and comments carry no stable id
				}
				String[] parts = line.split("\t", -1);
				if (parts.length < 4 + widest) {
					continue;
				}
				String kind = parts[1];
				String id = parts[3 + intermediary];
				String name = parts[3 + named];
				if (id.isEmpty() || name.isEmpty()) {
					continue;
				}
				if (idToHuman) {
					mappings.members.put(key(ownerId, kind, id), name);
				} else {
					String key = key(ownerName, kind, name);
					String previous = mappings.members.put(key, id);
					if (previous != null && !previous.equals(id)) {
						// two declarations share this human name (an overload): no single id is right
						mappings.members.remove(key);
						mappings.ambiguous.add(key);
					} else if (mappings.ambiguous.contains(key)) {
						mappings.members.remove(key);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("warning: cannot read mappings " + tiny + ": " + e.getMessage());
			return null;
		}
		return mappings;
	}

	private static String key(String owner, String kind, String name) {
		return owner + '\u0000' + kind + name;
	}

	/**
	 * Find the mapping file for a jar: an explicit path, else the loom cache entry whose id the jar
	 * path already carries ("layered+hash.<n>"). Nothing is downloaded.
	 */
	private static Path findMappings(Path jar, String explicit) {
		if (explicit != null) {
			Path given = Path.of(explicit);
			return Files.isRegularFile(given) ? given : null;
		}
		String text = jar.toAbsolutePath().toString().replace('\\', '/');
		Matcher hash = Pattern.compile("layered\\+hash\\.(\\d+)").matcher(text);
		if (!hash.find()) {
			return null;
		}
		String id = hash.group(1);
		Matcher version = Pattern.compile("(?:loom\\.mappings\\.|yarn\\.)(\\d+_\\d+(?:_\\d+)?)").matcher(text);
		String minecraft = version.find() ? version.group(1) : null;

		List<Path> roots = new ArrayList<>();
		String home = System.getProperty("user.home");
		if (home != null && minecraft != null) {
			roots.add(Path.of(home, ".gradle", "caches", "fabric-loom", minecraft.replace('_', '.')));
		}
		int depth = 0;
		for (Path parent = jar.toAbsolutePath().getParent(); parent != null && depth++ < 8;
				parent = parent.getParent()) {
			roots.add(parent.resolve(".gradle").resolve("loom-cache"));
		}
		for (Path root : roots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			Path found = searchMappings(root, minecraft, id);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static Path searchMappings(Path root, String minecraft, String id) {
		List<Path> tinys = new ArrayList<>();
		try (var walk = Files.walk(root, 5)) {
			walk.forEach(path -> {
				Path name = path.getFileName();
				if (name != null && name.toString().endsWith(".tiny") && Files.isRegularFile(path)) {
					tinys.add(path);
				}
			});
		} catch (IOException | RuntimeException e) {
			return null;
		}
		Collections.sort(tinys);
		Path loose = null;
		for (Path tiny : tinys) {
			String path = tiny.toString().replace('\\', '/');
			if (!path.contains("hash." + id) && !path.contains("+" + id)) {
				continue;
			}
			if (minecraft != null && !path.contains("loom.mappings." + minecraft)
					&& !path.contains("yarn." + minecraft)) {
				if (loose == null) {
					loose = tiny;
				}
				continue;
			}
			if (tiny.getFileName().toString().equals("mappings.tiny")) {
				return tiny;
			}
			loose = tiny;
		}
		return loose;
	}

	/** The member declaration for owner/name/kind in a jar, or null. */
	private static String[] find(Index index, String owner, String name, boolean method) {
		for (String[] member : (method ? index.methods : index.fields).getOrDefault(owner, List.of())) {
			if (member[0].equals(name)) {
				return member;
			}
		}
		return null;
	}

	private static String memberKey(Member member) {
		return member.owner() + "." + member.name() + (member.method() ? "()" : "");
	}

	/**
	 * Compiler artifacts (anonymous classes, lambdas, constructors) and unmapped intermediary names
	 * are not API renames, so the fallback rules leave them alone.
	 */
	private static boolean synthetic(String name) {
		if (name.startsWith("<") || name.startsWith("lambda$") || name.startsWith("method_")
				|| name.startsWith("field_")) {
			return true;
		}
		int dollar = name.lastIndexOf('$');
		if (dollar < 0 || dollar == name.length() - 1) {
			return false;
		}
		for (int i = dollar + 1; i < name.length(); i++) {
			if (!Character.isDigit(name.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A removed member reused under a new name still sits in a class present in both jars. Collect
	 * those removals once so the diff and the link step agree on the same list.
	 */
	private static List<Member> removedMembers(Index before, Index after) {
		List<Member> removed = new ArrayList<>();
		List<Signature> ignored = new ArrayList<>();
		Map<String, Tally> ignoredTallies = new TreeMap<>();
		for (String owner : before.classes) {
			if (!after.classes.contains(owner)) {
				continue;
			}
			cases(owner, true, before.methods, after.methods, removed, ignored, ignoredTallies);
			cases(owner, false, before.fields, after.fields, removed, ignored, ignoredTallies);
		}
		return removed;
	}

	/** Member names that exist only in the new class, used by the same-descriptor fallback. */
	private static String fingerprint(Index index, String owner) {
		List<String[]> methods = index.methods.getOrDefault(owner, List.of());
		List<String[]> fields = index.fields.getOrDefault(owner, List.of());
		if (methods.size() + fields.size() < 3) {
			return null; // too few members to mean anything
		}
		List<String> names = new ArrayList<>();
		for (String[] member : methods) {
			names.add("m:" + member[0]);
		}
		for (String[] member : fields) {
			names.add("f:" + member[0]);
		}
		Collections.sort(names);
		return String.join(",", names);
	}

	// ------------------------------------------------------------------ diffing

	private static String diff(Path oldJar, Path newJar, Index before, Index after, Links links) {
		Set<String> removedClasses = new TreeSet<>(before.classes);
		removedClasses.removeAll(after.classes);
		Set<String> addedClasses = new TreeSet<>(after.classes);
		addedClasses.removeAll(before.classes);

		Map<String, List<String>> removedBySimple = groupBySimple(removedClasses);
		Map<String, List<String>> addedBySimple = groupBySimple(addedClasses);

		List<Move> moves = new ArrayList<>();
		Set<String> movedFrom = new HashSet<>();
		Set<String> movedTo = new HashSet<>();
		for (var entry : removedBySimple.entrySet()) {
			List<String> wants = new ArrayList<>(entry.getValue());
			List<String> offers = addedBySimple.getOrDefault(entry.getKey(), List.of());
			if (offers.isEmpty()) {
				continue;
			}
			List<String> remainingOffers = new ArrayList<>(offers);
			Collections.sort(wants);
			if (wants.size() == 1 && offers.size() == 1) {
				String from = wants.get(0);
				String to = offers.get(0);
				if (!packageOf(from).equals(packageOf(to))) {
					moves.add(new Move(from, to, entry.getKey(), "unique"));
					movedFrom.add(from);
					movedTo.add(to);
				}
				continue;
			}
			// Several classes share the simple name: take the best package match first.
			for (String from : wants) {
				String best = null;
				int bestScore = Integer.MIN_VALUE;
				for (String to : remainingOffers) {
					int score = score(from, to);
					if (score > bestScore) {
						bestScore = score;
						best = to;
					}
				}
				if (best == null) {
					break;
				}
				remainingOffers.remove(best);
				if (!packageOf(from).equals(packageOf(best))) {
					moves.add(new Move(from, best, entry.getKey(), "nearest"));
					movedFrom.add(from);
					movedTo.add(best);
				}
			}
		}
		moves.sort(Comparator.comparing(Move::from));

		List<Member> membersRemoved = new ArrayList<>();
		List<Signature> signatures = new ArrayList<>();
		Map<String, Tally> perClass = new TreeMap<>();

		for (String owner : before.classes) {
			if (movedFrom.contains(owner)) {
				perClass.computeIfAbsent(owner, k -> new Tally()).status = "moved";
				continue;
			}
			if (!after.classes.contains(owner)) {
				continue; // reported as a removal, not as member noise
			}
			compareMembers(owner, true, before.methods, after.methods, membersRemoved, signatures, perClass);
			compareMembers(owner, false, before.fields, after.fields, membersRemoved, signatures, perClass);
		}

		// A stable-id link already explains a removal, so it is not also a class add or delete.
		Set<String> explainedFrom = new HashSet<>();
		Set<String> explainedTo = new HashSet<>();
		int stableLinks = 0;
		int heuristicLinks = 0;
		for (Replacement replacement : links.all) {
			if ("stable-id".equals(replacement.evidence())) {
				stableLinks++;
			} else {
				heuristicLinks++;
			}
			if (!"class".equals(replacement.kind())) {
				continue;
			}
			if (removedClasses.contains(replacement.from()) && addedClasses.contains(replacement.to())) {
				explainedFrom.add(replacement.from());
				explainedTo.add(replacement.to());
			}
		}

		long movedCount = moves.size();
		long classesRemovedCount = removedClasses.size() - movedCount - explainedFrom.size();
		long classesAddedCount = addedClasses.size() - movedTo.size() - explainedTo.size();
		long methodsRemoved = membersRemoved.stream().filter(Member::method).count();
		long fieldsRemoved = membersRemoved.size() - methodsRemoved;

		var counts = new LinkedHashMap<String, Long>();
		counts.put("oldClasses", (long) before.classes.size());
		counts.put("newClasses", (long) after.classes.size());
		counts.put("classesRemoved", classesRemovedCount);
		counts.put("classesAdded", classesAddedCount);
		counts.put("classesMoved", movedCount);
		counts.put("methodsRemoved", methodsRemoved);
		counts.put("fieldsRemoved", fieldsRemoved);
		counts.put("membersRemoved", (long) membersRemoved.size());
		counts.put("signatureChanges", (long) signatures.size());
		counts.put("replacements", (long) links.all.size());
		counts.put("replacementsStable", (long) stableLinks);
		counts.put("replacementsHeuristic", (long) heuristicLinks);
		counts.put("classOverlap", (long) (before.classes.size() - removedClasses.size()));
		counts.put("skippedClasses", (long) (before.skipped + after.skipped));

		// A low overlap means the two jars are not in the same mapping family; say so once.
		double overlap = before.classes.isEmpty() ? 0
				: (before.classes.size() - removedClasses.size()) / (double) before.classes.size();
		List<String> notes = new ArrayList<>();
		if (overlap < 0.5) {
			notes.add(String.format(
					"only %.0f%% of old classes exist under the same name in the new jar: the two jars are probably not in the same mapping family, so most entries below are name noise",
					overlap * 100));
		}
		notes.add(String.format(
				"%d replacements: %d proven by a stable obfuscated id, %d guessed by a fallback rule",
				links.all.size(), stableLinks, heuristicLinks));
		if (links.old != null && links.old.cleared != null) {
			notes.add("old mappings " + links.old.file + " ignored: " + links.old.cleared);
		}
		if (links.recent != null && links.recent.cleared != null) {
			notes.add("new mappings " + links.recent.file + " ignored: " + links.recent.cleared);
		}
		if (links.oldNote != null) {
			notes.add(links.oldNote);
		}
		if (links.recentNote != null) {
			notes.add(links.recentNote);
		}
		if (explainedFrom.size() > 0) {
			notes.add(String.format(
					"%d removed classes and %d added classes are one renamed class each, so they are left out of the removed/added lists",
					explainedFrom.size(), explainedTo.size()));
		}

		var json = new StringBuilder();
		json.append("{\n");
		json.append("  \"old\": ").append(q(oldJar.toString())).append(",\n");
		json.append("  \"new\": ").append(q(newJar.toString())).append(",\n");
		json.append("  \"generated\": ").append(q(java.time.OffsetDateTime.now().toString())).append(",\n");
		json.append("  \"counts\": {");
		boolean first = true;
		for (var entry : counts.entrySet()) {
			json.append(first ? "\n" : ",\n").append("    ").append(q(entry.getKey())).append(": ")
					.append(entry.getValue());
			first = false;
		}
		json.append("\n  },\n");

		// how the links were made, so a reader knows whether to trust them
		json.append("  \"linking\": {\n");
		json.append("    \"namespace\": ").append(q("intermediary")).append(",\n");
		json.append("    \"oldMappings\": ").append(q(links.old == null ? "" : links.old.file.toString()))
				.append(",\n");
		json.append("    \"newMappings\": ").append(q(links.recent == null ? "" : links.recent.file.toString()))
				.append(",\n");
		json.append("    \"oldMappingAgreement\": ")
				.append(String.format(Locale.ROOT, "%.3f", links.old == null ? 0 : links.old.classAgreement))
				.append(",\n");
		json.append("    \"newMappingAgreement\": ")
				.append(String.format(Locale.ROOT, "%.3f", links.recent == null ? 0 : links.recent.classAgreement))
				.append(",\n");
		json.append("    \"stable\": ").append(stableLinks).append(",\n");
		json.append("    \"heuristic\": ").append(heuristicLinks).append(",\n");
		json.append("    \"unlinkedClasses\": ").append(links.unlinkedClasses).append(",\n");
		json.append("    \"unlinkedMembers\": ").append(links.unlinkedMembers).append("\n");
		json.append("  },\n");

		// replacements: the whole point of the link step
		links.all.sort(Comparator.comparing(Replacement::kind).thenComparing(Replacement::from));
		json.append("  \"replacements\": [");
		first = true;
		for (Replacement replacement : links.all) {
			json.append(first ? "\n" : ",\n");
			first = false;
			json.append("    {\"from\": ").append(q(replacement.from())).append(", \"to\": ")
					.append(q(replacement.to())).append(", \"kind\": ").append(q(replacement.kind()));
			if (replacement.stableId() != null) {
				json.append(", \"stableId\": ").append(q(replacement.stableId()));
			}
			if (replacement.descriptor() != null) {
				json.append(", \"descriptor\": ").append(q(replacement.descriptor()));
			}
			if (replacement.newDescriptor() != null) {
				json.append(", \"newDescriptor\": ").append(q(replacement.newDescriptor()));
			}
			json.append(", \"evidence\": ").append(q(replacement.evidence()))
					.append(", \"match\": ").append(q(replacement.match())).append("}");
		}
		json.append("\n  ],\n");

		// classes removed
		json.append("  \"classesRemoved\": [");
		first = true;
		for (String owner : removedClasses) {
			if (movedFrom.contains(owner) || explainedFrom.contains(owner)) {
				continue;
			}
			json.append(first ? "\n" : ",\n");
			first = false;
			json.append("    {\"class\": ").append(q(owner)).append(", \"package\": ")
					.append(q(packageOf(owner))).append(", \"simple\": ").append(q(simpleOf(owner))).append("}");
		}
		json.append("\n  ],\n");

		// classes added (useful to spot a replacement for a removed one)
		json.append("  \"classesAdded\": [");
		first = true;
		for (String owner : addedClasses) {
			if (movedTo.contains(owner) || explainedTo.contains(owner)) {
				continue;
			}
			json.append(first ? "\n" : ",\n");
			first = false;
			json.append("    {\"class\": ").append(q(owner)).append(", \"package\": ")
					.append(q(packageOf(owner))).append(", \"simple\": ").append(q(simpleOf(owner))).append("}");
		}
		json.append("\n  ],\n");

		// classes moved
		json.append("  \"classesMoved\": [");
		first = true;
		for (Move move : moves) {
			json.append(first ? "\n" : ",\n");
			first = false;
			json.append("    {\"class\": ").append(q(move.from())).append(", \"to\": ")
					.append(q(move.to())).append(", \"simple\": ").append(q(move.simple()))
					.append(", \"package\": ").append(q(packageOf(move.from()))).append(", \"newPackage\": ")
					.append(q(packageOf(move.to()))).append(", \"match\": ").append(q(move.confidence()))
					.append("}");
		}
		json.append("\n  ],\n");

		// members removed
		membersRemoved.sort(Comparator.comparing(Member::owner).thenComparing(Member::name)
				.thenComparing(Member::descriptor));
		json.append("  \"membersRemoved\": [");
		first = true;
		for (Member member : membersRemoved) {
			json.append(first ? "\n" : ",\n");
			first = false;
			json.append("    {\"class\": ").append(q(member.owner())).append(", \"kind\": ")
					.append(q(member.method() ? "method" : "field")).append(", \"name\": ")
					.append(q(member.name())).append(", \"descriptor\": ").append(q(member.descriptor()))
					.append("}");
		}
		json.append("\n  ],\n");

		// signature changes
		signatures.sort(Comparator.comparing(Signature::owner).thenComparing(Signature::name));
		json.append("  \"signatureChanges\": [");
		first = true;
		for (Signature change : signatures) {
			json.append(first ? "\n" : ",\n");
			first = false;
			json.append("    {\"class\": ").append(q(change.owner())).append(", \"kind\": ")
					.append(q(change.kind())).append(", \"name\": ").append(q(change.name()))
					.append(", \"oldDescriptor\": ").append(q(change.oldDescriptor()))
					.append(", \"newDescriptor\": ").append(q(change.newDescriptor())).append("}");
		}
		json.append("\n  ],\n");

		// per-class summary
		json.append("  \"perClass\": {");
		first = true;
		for (var entry : perClass.entrySet()) {
			Tally tally = entry.getValue();
			json.append(first ? "\n" : ",\n");
			first = false;
			json.append("    ").append(q(entry.getKey())).append(": {\"status\": ").append(q(tally.status))
					.append(", \"methodsRemoved\": ").append(tally.methodsRemoved)
					.append(", \"fieldsRemoved\": ").append(tally.fieldsRemoved)
					.append(", \"signatureChanges\": ").append(tally.signatures).append("}");
		}
		json.append("\n  },\n");

		// top classes by change weight, so the report has a ranked list without reading perClass
		List<Map.Entry<String, Tally>> ranked = new ArrayList<>(perClass.entrySet());
		ranked.sort((a, b) -> Integer.compare(weight(b.getValue()), weight(a.getValue())));
		json.append("  \"topChangedClasses\": [");
		first = true;
		int shown = 0;
		for (var entry : ranked) {
			if (shown++ >= 25 || weight(entry.getValue()) == 0 && !"removed".equals(entry.getValue().status)) {
				break;
			}
			Tally tally = entry.getValue();
			json.append(first ? "\n" : ",\n");
			first = false;
			json.append("    {\"class\": ").append(q(entry.getKey())).append(", \"status\": ")
					.append(q(tally.status)).append(", \"methodsRemoved\": ").append(tally.methodsRemoved)
					.append(", \"fieldsRemoved\": ").append(tally.fieldsRemoved)
					.append(", \"signatureChanges\": ").append(tally.signatures).append("}");
		}
		json.append("\n  ],\n");

		json.append("  \"notes\": [");
		first = true;
		for (String note : notes) {
			json.append(first ? "\n" : ",\n").append("    ").append(q(note));
			first = false;
		}
		json.append("\n  ]\n");
		json.append("}");
		return json.toString();
	}

	private static int weight(Tally tally) {
		return tally.methodsRemoved * 2 + tally.fieldsRemoved + tally.signatures * 2;
	}

	/**
	 * Compare one member table for a class that exists in both jars. A name that still exists with
	 * a different descriptor in a class without overloads is a signature change, not a removal.
	 */
	private static void compareMembers(String owner, boolean method,
			Map<String, List<String[]>> before, Map<String, List<String[]>> after,
			List<Member> removed, List<Signature> signatures, Map<String, Tally> perClass) {
		cases(owner, method, before, after, removed, signatures, perClass);
	}

	/** The diff body, shared with the link step so both see exactly the same removals. */
	private static void cases(String owner, boolean method,
			Map<String, List<String[]>> before, Map<String, List<String[]>> after,
			List<Member> removed, List<Signature> signatures, Map<String, Tally> perClass) {
		List<String[]> oldMembers = before.getOrDefault(owner, List.of());
		if (oldMembers.isEmpty()) {
			return;
		}
		List<String[]> newMembers = after.getOrDefault(owner, List.of());
		Map<String, List<String>> newByName = new HashMap<>();
		for (String[] member : newMembers) {
			newByName.computeIfAbsent(member[0], k -> new ArrayList<>()).add(member[1]);
		}
		Map<String, List<String>> oldByName = new LinkedHashMap<>();
		for (String[] member : oldMembers) {
			oldByName.computeIfAbsent(member[0], k -> new ArrayList<>()).add(member[1]);
		}
		for (var entry : oldByName.entrySet()) {
			String name = entry.getKey();
			List<String> oldDescriptors = entry.getValue();
			List<String> newDescriptors = newByName.getOrDefault(name, List.of());
			if (newDescriptors.isEmpty()) {
				for (String descriptor : oldDescriptors) {
					removed.add(new Member(owner, name, descriptor, method));
					Tally tally = perClass.computeIfAbsent(owner, k -> new Tally());
					if (method) {
						tally.methodsRemoved++;
					} else {
						tally.fieldsRemoved++;
					}
				}
				continue;
			}
			List<String> gone = new ArrayList<>(oldDescriptors);
			gone.removeAll(newDescriptors);
			for (String descriptor : gone) {
				if (oldDescriptors.size() == 1) {
					// no overload: the same name with another descriptor is a signature change
					signatures.add(new Signature(owner, name, method ? "method" : "field", descriptor,
							nearest(descriptor, newDescriptors)));
					perClass.computeIfAbsent(owner, k -> new Tally()).signatures++;
				} else {
					removed.add(new Member(owner, name, descriptor, method));
					Tally tally = perClass.computeIfAbsent(owner, k -> new Tally());
					if (method) {
						tally.methodsRemoved++;
					} else {
						tally.fieldsRemoved++;
					}
				}
			}
		}
	}

	/** Closest descriptor in the new jar, so a changed signature points at its replacement. */
	private static String nearest(String descriptor, List<String> candidates) {
		String best = candidates.get(0);
		int bestDistance = Integer.MAX_VALUE;
		for (String candidate : candidates) {
			int distance = editDistance(descriptor, candidate);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	private static int editDistance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}

	/** Higher is a better package match; the caller only compares scores within one simple name. */
	private static int score(String from, String to) {
		String[] a = packageOf(from).split("/");
		String[] b = packageOf(to).split("/");
		int common = 0;
		while (common < a.length && common < b.length && a[common].equals(b[common])) {
			common++;
		}
		int depthGap = Math.abs(a.length - b.length);
		boolean suffix = b.length >= a.length && String.join("/", b).endsWith(packageOf(from));
		return common * 100 - depthGap * 10 + (suffix ? 50 : 0);
	}

	private static Map<String, List<String>> groupBySimple(Set<String> classes) {
		Map<String, List<String>> out = new TreeMap<>();
		for (String owner : classes) {
			out.computeIfAbsent(simpleOf(owner), k -> new ArrayList<>()).add(owner);
		}
		return out;
	}

	private static String simpleOf(String internalName) {
		int slash = internalName.lastIndexOf('/');
		return slash < 0 ? internalName : internalName.substring(slash + 1);
	}

	private static String packageOf(String internalName) {
		int slash = internalName.lastIndexOf('/');
		return slash < 0 ? "" : internalName.substring(0, slash);
	}

	/** Minimal JSON string escaping; class names and descriptors only need backslash and quote care. */
	private static String q(String text) {
		var out = new StringBuilder(text.length() + 2);
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
		return out.toString();
	}
}
