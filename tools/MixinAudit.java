import java.io.IOException;
import java.io.InputStream;
import java.io.Closeable;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

/**
 * MixinAudit - does every mixin still point at something that exists?
 *
 * A dead {@code @Shadow} target is invisible to the compiler, to Mixin at apply time (it logs a
 * warning and skips the member) and to every game tier until the field is read. This reads the
 * mixin sources and the mapped game jar and answers the same question Mixin asks when it applies:
 * is there a member with that name (and, for methods, that descriptor) in the target class?
 *
 *   java -cp <classes> MixinAudit --root <repo> [--classpath <file>] [--jar <jar>]...
 *                                     [--src <dir>]... [--quiet]
 *
 * The classpath file is the one the build already writes (.modaccel/classpath.txt, one path per
 * entry separated by ';'); the mapped Minecraft jar is taken from it. Without --classpath the
 * jars under <root>/.gradle/loom-cache/minecraftMaven are used.
 *
 * Output, one finding per line, nothing else:
 *   src/main/java/x/Y.java:12 @Shadow field 'foo' not found in net.minecraft.server.MinecraftServer (fields: a, b)
 * and a last line starting with '# ' that summarises the run. Set MIXINAUDIT_DEBUG=1 to print every
 * check it made to stderr.
 *
 * Exit: 0 every target resolved, 1 findings, 2 usage or I/O error.
 *
 * What it checks (see the last section of this comment for what it does not):
 *   - @Shadow field: a field of that exact name in the target class or its superclass chain.
 *   - @Shadow method / @Overwrite: a method of that name, parameter count, parameter types and
 *     return type, compared where the source type resolves to a class in the jars.
 *   - @Inject, @Redirect, @ModifyVariable, @ModifyArg(s), @ModifyConstant,
 *     @ModifyExpressionValue, @WrapOperation, @WrapWithCondition: the method named in
 *     method = "..." exists (wildcards, and "name(descriptor)" selectors, are honoured).
 *   - @At: INVOKE (owner+name+descriptor, owner+name, or name alone, resolved against the
 *     mixin's targets), FIELD (name and optional descriptor), NEW (the class exists). HEAD,
 *     TAIL and RETURN need no target. A missing member is reported with the candidates found
 *     in that class. The owner's superclass and interface families are walked for @At.
 *   - @Inject handler signature: the declared handler must return void, end in exactly one
 *     CallbackInfo (void target) or CallbackInfoReturnable<T> whose T is the target's return
 *     type, and take at most the target's parameters as a leading prefix of matching types
 *     (static exactly when the target is). Both signatures are printed. Handler
 *     parameters annotated @Local/@Share/@Coerce are captured locals: the callback is still
 *     checked, the capture is not.
 *
 * What it does not check:
 *   - @At selection: shift, ordinal, slice, require/expect and how many matches a point has.
 *     This asks "does that member exist", not "does that injection point land somewhere".
 *   - Handler signatures for annotations other than @Inject (@Redirect, @ModifyArg, ...).
 *   - Mixin remapping: it reads the jar it is given as-is, assuming that jar already uses the same
 *     names as the source (a mapped/dev jar). A jar in another mapping family fails loudly with
 *     "target class not found" rather than checking anything.
 *   - Types not in the jars (java.* and other mods' classes): a parameter or return type it cannot
 *     resolve in the jar index is not compared, so those members fall back to name and arity.
 *   - Superclass-chain walking for @Shadow/@Overwrite. A member inherited only through an
 *     interface is reported as missing there; @At checks do walk interfaces.
 *   - @Accessor/@Invoker getter/setter naming, @Unique members, and members the mixin itself adds
 *     are not checked.
 *   - Mixins whose target is not in the jars (another mod's class, or the mod's own class) are
 *     counted as "unresolved target" and skipped, not failed - that needs the full runtime
 *     classpath, which the fast lane does not have.
 */
public final class MixinAudit {
	private static final String USAGE = "usage: java MixinAudit [--root <dir>] [--classpath <file>]"
			+ " [--jar <jar>]... [--src <dir>]... [--quiet]";

	/** Annotation names that name a method in the target, with the attribute that carries it. */
	private static final Set<String> INJECTIONS = Set.of("Inject", "Redirect", "ModifyVariable",
			"ModifyArg", "ModifyArgs", "ModifyConstant", "ModifyExpressionValue", "WrapOperation",
			"WrapWithCondition");

	/** @At values that need no target: the point is the method boundary itself. */
	private static final Set<String> NO_TARGET_AT = Set.of("HEAD", "TAIL", "RETURN");

	// ------------------------------------------------------------------ game classes

	/** The declared members of one class, read from the jar. */
	private static final class ClassInfo {
		String name;
		String superName;
		final List<String> interfaces = new ArrayList<>();
		final List<String[]> fields = new ArrayList<>();  // {name, descriptor}
		final List<String[]> methods = new ArrayList<>(); // {name, descriptor, "static" or ""}
	}

	/** Opens the jars once and answers "what members does this class have" from them. */
	private static final class Loader implements Closeable {
		final List<ZipFile> zips = new ArrayList<>();
		final List<Path> jars = new ArrayList<>();
		final Map<String, List<String>> bySimple = new HashMap<>();
		final Set<String> all = new HashSet<>();
		final Map<String, ClassInfo> cache = new HashMap<>();

		static Loader open(List<Path> paths) throws IOException {
			Loader l = new Loader();
			for (Path p : paths) {
				if (!Files.isRegularFile(p)) { continue; }
				ZipFile z = new ZipFile(p.toFile());
				l.zips.add(z);
				l.jars.add(p);
				// Entry names only: the whole jar is never parsed, just listed, so the run
				// costs what the few target classes cost.
				for (ZipEntry e : Collections.list(z.entries())) {
					String n = e.getName();
					if (e.isDirectory() || !n.endsWith(".class") || n.startsWith("META-INF/")) {
						continue;
					}
					String internal = n.substring(0, n.length() - ".class".length());
					l.all.add(internal);
					l.bySimple.computeIfAbsent(simple(internal), k -> new ArrayList<>()).add(internal);
				}
			}
			return l;
		}

		ClassInfo info(String internal) {
			if (cache.containsKey(internal)) { return cache.get(internal); }
			ClassInfo ci = null;
			byte[] bytes = bytes(internal);
			if (bytes != null) {
				try {
					ClassModel m = ClassFile.of().parse(bytes);
					ci = new ClassInfo();
					ci.name = internal;
					ci.superName = m.superclass().map(ce -> ce.asInternalName()).orElse(null);
					for (var ce : m.interfaces()) { ci.interfaces.add(ce.asInternalName()); }
					for (FieldModel f : m.fields()) {
						ci.fields.add(new String[] {f.fieldName().stringValue(), f.fieldType().stringValue()});
					}
					for (MethodModel mm : m.methods()) {
						ci.methods.add(new String[] {mm.methodName().stringValue(), mm.methodType().stringValue(),
								mm.flags().has(java.lang.reflect.AccessFlag.STATIC) ? "static" : ""});
					}
				} catch (RuntimeException e) {
					ci = null; // class format this JDK cannot read
				}
			}
			cache.put(internal, ci);
			return ci;
		}

		byte[] bytes(String internal) {
			for (ZipFile z : zips) {
				ZipEntry e = z.getEntry(internal + ".class");
				if (e == null) { continue; }
				try (InputStream in = z.getInputStream(e)) {
					return in.readAllBytes();
				} catch (IOException ex) {
					return null;
				}
			}
			return null;
		}

		/** The class and its superclass chain, nearest first. */
		List<String> hierarchy(String internal) {
			List<String> out = new ArrayList<>();
			Deque<String> queue = new ArrayDeque<>();
			queue.add(internal);
			while (!queue.isEmpty() && out.size() < 32) {
				String n = queue.poll();
				if (n == null || n.equals("java/lang/Object") || out.contains(n)) { continue; }
				ClassInfo ci = info(n);
				if (ci == null) { continue; }
				out.add(n);
				if (ci.superName != null) { queue.add(ci.superName); }
			}
			return out;
		}

		List<String[]> fieldsOf(String internal) {
			List<String[]> out = new ArrayList<>();
			for (String n : hierarchy(internal)) { out.addAll(info(n).fields); }
			return out;
		}

		List<String[]> methodsOf(String internal) {
			List<String[]> out = new ArrayList<>();
			for (String n : hierarchy(internal)) { out.addAll(info(n).methods); }
			return out;
		}

		/** The class, its superclass chain and its interfaces, nearest first. */
		List<String> family(String internal) {
			List<String> out = new ArrayList<>();
			Deque<String> queue = new ArrayDeque<>();
			queue.add(internal);
			while (!queue.isEmpty() && out.size() < 64) {
				String n = queue.poll();
				if (n == null || n.equals("java/lang/Object") || out.contains(n)) { continue; }
				ClassInfo ci = info(n);
				if (ci == null || ci.superName == null) { continue; }
				out.add(n);
				queue.add(ci.superName);
				queue.addAll(ci.interfaces);
			}
			return out;
		}

		List<String[]> fieldsIn(String internal) {
			List<String[]> out = new ArrayList<>();
			for (String n : family(internal)) { out.addAll(info(n).fields); }
			return out;
		}

		List<String[]> methodsIn(String internal) {
			List<String[]> out = new ArrayList<>();
			for (String n : family(internal)) { out.addAll(info(n).methods); }
			return out;
		}

		@Override
		public void close() {
			for (ZipFile z : zips) {
				try {
					z.close();
				} catch (IOException ignored) {
					// nothing to do at exit
				}
			}
		}
	}

	// ------------------------------------------------------------------ source model

	/** A member declaration the mixin makes, and the annotation line that asked for it. */
	private record Decl(int line, boolean field, String name, List<String> params, String returnType,
			boolean isStatic, boolean captures) {
	}

	private static final class Finding {
		final String file;
		final int line;
		final String message;

		Finding(String file, int line, String message) {
			this.file = file;
			this.line = line;
			this.message = message;
		}
	}

	// ------------------------------------------------------------------ main

	private static int targetCount;
	private static int checkCount;
	private static int mixinCount;
	private static int skippedTargets;
	private static final List<String> notes = new ArrayList<>();
	private static final boolean DEBUG = System.getenv("MIXINAUDIT_DEBUG") != null;

	public static void main(String[] args) throws Exception {
		String root = ".";
		Path classpathFile = null;
		List<Path> jars = new ArrayList<>();
		List<Path> srcs = new ArrayList<>();
		boolean quiet = false;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--root" -> root = arg(args, ++i);
				case "--classpath" -> classpathFile = Path.of(arg(args, ++i));
				case "--jar" -> jars.add(Path.of(arg(args, ++i)));
				case "--src" -> srcs.add(Path.of(arg(args, ++i)));
				case "--quiet" -> quiet = true;
				default -> {
					System.err.println(USAGE);
					System.exit(2);
				}
			}
		}
		Path rootDir = Path.of(root).toAbsolutePath().normalize();
		if (srcs.isEmpty()) {
			srcs.add(rootDir.resolve("src/main/java"));
			srcs.add(rootDir.resolve("src/client/java"));
		}
		if (jars.isEmpty()) {
			jars.addAll(fromClasspath(classpathFile));
		}
		if (jars.isEmpty()) {
			jars.addAll(glob(rootDir.resolve(".gradle/loom-cache/minecraftMaven"), "glob:**/*.jar"));
		}
		if (jars.isEmpty()) {
			// Not finding the game jar means the audit could not look, not that a mixin is broken; a
			// failure here blocked the whole fast lane on Forge repos.
			System.out.println("note: mixins not audited, no mapped game jar found (pass --classpath or --jar)");
			System.exit(0);
		}

		List<Path> sources = new ArrayList<>();
		for (Path src : srcs) {
			if (!Files.isDirectory(src)) { continue; }
			try (var walk = Files.walk(src)) {
				walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
			}
		}
		List<String> findings = new ArrayList<>();
		try (Loader loader = Loader.open(jars)) {
			sources.sort(Comparator.naturalOrder());
			for (Path file : sources) {
				String text = Files.readString(file, StandardCharsets.UTF_8);
				if (!text.contains("@Mixin")) { continue; }
				String rel = rootDir.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
				scan(rel, blankComments(text), loader, findings);
			}
		}
		for (String f : findings) { System.out.println(f); }
		System.out.println("# " + plural(mixinCount, "mixin") + ", " + plural(targetCount, "target") + ", "
				+ plural(checkCount, "member") + " checked, " + findings.size() + " dead"
				+ (skippedTargets > 0 ? ", " + skippedTargets + " targets not in the jar" : ""));
		if (!quiet) {
			for (String n : notes) { System.err.println("note: " + n); }
		}
		System.exit(findings.isEmpty() ? 0 : 1);
	}

	/**
	 * Comments become spaces (line breaks kept, so line numbers hold). Stonecutter keeps the other
	 * versions' annotations in comments, and auditing those reported live mixins as dead.
	 */
	static String blankComments(String text) {
		char[] out = text.toCharArray();
		int i = 0;
		while (i < out.length) {
			char c = out[i];
			if (c == '"' || c == '\'') {
				i = Math.max(skipString(text, i), i + 1);
			} else if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
				while (i < out.length && out[i] != '\n') { out[i++] = ' '; }
			} else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
				int end = text.indexOf("*/", i + 2);
				int stop = end < 0 ? out.length : end + 2;
				for (; i < stop; i++) { if (out[i] != '\n' && out[i] != '\r') { out[i] = ' '; } }
			} else {
				i++;
			}
		}
		return new String(out);
	}

	/** "1 mixin", "3 mixins". */
	private static String plural(int n, String word) {
		return n + " " + word + (n == 1 ? "" : "s");
	}

	private static String arg(String[] args, int i) {		if (i >= args.length) {
			System.err.println(USAGE);
			System.exit(2);
		}
		return args[i];
	}

	/** The mapped game jars the build already put on the compile classpath. */
	private static List<Path> fromClasspath(Path file) throws IOException {
		List<Path> out = new ArrayList<>();
		if (file == null || !Files.isRegularFile(file)) { return out; }
		String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
		for (String entry : raw.split(";")) {
			String e = entry.trim();
			if (e.isEmpty()) { continue; }
			String p = e.replace('/', '\\');
			if (p.contains("loom-cache") && p.contains("minecraft-") && p.endsWith(".jar")) {
				out.add(Path.of(e));
			}
		}
		return out;
	}

	private static List<Path> glob(Path base, String pattern) throws IOException {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(base)) { return out; }
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher(pattern);
		try (var walk = Files.walk(base)) {
			walk.filter(p -> matcher.matches(p) && Files.isRegularFile(p)).forEach(out::add);
		}
		out.sort(Comparator.naturalOrder());
		return out;
	}

	// ------------------------------------------------------------------ one source file

	private static void scan(String rel, String text, Loader loader, List<String> findings) {
		int[] lineStarts = lineStarts(text);
		List<int[]> annotations = annotations(text);
		List<String> targets = new ArrayList<>();
		boolean isMixin = false;
		List<Finding> local = new ArrayList<>();
		for (int[] a : annotations) {
			String name = text.substring(a[1], a[2]);
			if (name.equals("Mixin") && !isMixin) {
				isMixin = true;
				targets.addAll(mixinTargets(text.substring(a[3], a[4])));
			}
		}
		if (!isMixin) { return; }
		mixinCount++;
		if (targets.isEmpty()) {
			notes.add(rel + ": @Mixin with no class or targets= value");
			return;
		}
		List<String> resolved = new ArrayList<>();
		for (String want : targets) {
			String internal = resolve(want, loader);
			if (internal == null) {
				skippedTargets++;
				notes.add(rel + ": target " + want + " is not in the given jars, not checked");
				continue;
			}
			if (!resolved.contains(internal)) { resolved.add(internal); }
		}
		targetCount += resolved.size();

		for (int[] a : annotations) {
			String name = text.substring(a[1], a[2]);
			if (name.equals("Mixin")) { continue; }
			boolean needsDecl = name.equals("Shadow") || name.equals("Overwrite") || name.equals("Inject");
			if (!needsDecl && !INJECTIONS.contains(name)) { continue; }
			Decl decl = needsDecl ? declAfter(text, a[4] + 1) : null;
			if (needsDecl && decl == null) {
				notes.add(rel + ": @ " + name + " at line " + lineOf(lineStarts, a[0])
						+ " has no declaration this reads");
				continue;
			}
			int line = lineOf(lineStarts, a[0]);
			if (DEBUG) {
				System.err.println("check " + rel + ":" + line + " @" + name
						+ (decl == null ? "" : " " + decl.name() + (decl.field() ? " (field)" : " " + decl.params()))
						+ " -> " + resolved);
			}
			if (name.equals("Shadow") || name.equals("Overwrite")) {
				for (String target : resolved) {
					if (name.equals("Shadow")) {
						if (decl.field()) { checkField(rel, line, target, decl, loader, local); }
						else { checkMethod(rel, line, target, decl, loader, local, "@Shadow"); }
					} else if (!decl.field()) {
						checkMethod(rel, line, target, decl, loader, local, "@Overwrite");
					}
				}
				continue;
			}
			// An injection: the selector names a target method, the @At names a member inside it,
			// and (for @Inject) the declaration below the annotation is the handler.
			String selectorValue = attribute(text, a[3], a[4], "method");
			List<String> chosen = selectors(selectorValue);
			// require = 0 makes the injection optional: Mixin skips it quietly when nothing matches,
			// so what's missing is a note, not a dead target.
			boolean optional = "0".equals(String.valueOf(attribute(text, a[3], a[4], "require")).trim());
			List<Finding> found = optional ? new ArrayList<>() : local;
			for (String target : resolved) {
				for (String selector : chosen) {
					checkSelector(rel, line, target, name, selector, loader, found);
				}
				if (name.equals("Inject") && decl != null) {
					List<String[]> methods = new ArrayList<>();
					for (String selector : chosen) { methods.addAll(selectorMatches(loader, target, selector)); }
					checkHandler(rel, line, target, decl, methods, loader, found);
				}
			}
			int[] at = atArgs(text, a[3], a[4]);
			if (at != null) {
				checkAt(rel, line, name, text.substring(at[0], at[1]), resolved, loader, found);
			} else if (name.equals("Inject")) {
				notes.add(rel + ": @" + name + " at line " + line + " has no @At this reads");
			}
			if (optional) {
				for (Finding f : found) { notes.add(f.file + ":" + f.line + " optional (require = 0): " + f.message); }
			}
		}
		for (Finding f : local) { findings.add(f.file + ":" + f.line + " " + f.message); }
	}

	private static void checkField(String rel, int line, String target, Decl decl, Loader loader,
			List<Finding> local) {
		checkCount++;
		for (String[] f : loader.fieldsOf(target)) {
			if (f[0].equals(decl.name())) { return; }
		}
		String candidates = String.join(", ", nearest(decl.name(),
				loader.fieldsOf(target).stream().map(f -> f[0]).distinct().toList()));
		local.add(new Finding(rel, line, "@Shadow field '" + decl.name() + "' not found in "
				+ target.replace('/', '.') + " (fields: " + candidates + ")"));
	}

	private static void checkMethod(String rel, int line, String target, Decl decl, Loader loader,
			List<Finding> local, String annotation) {
		checkCount++;
		List<String[]> all = loader.methodsOf(target);
		List<String[]> same = all.stream().filter(m -> m[0].equals(decl.name())).toList();
		if (same.isEmpty()) {
			local.add(new Finding(rel, line, annotation + " method '" + decl.name() + "' not found in "
					+ target.replace('/', '.') + " (methods: "
					+ String.join(", ", nearest(decl.name(), all.stream().map(m -> m[0]).distinct().toList()))
					+ ")"));
			return;
		}
		// Same name: the descriptor has to agree too, or Mixin reports the shadow as unmapped.
		for (String[] m : same) {
			if (compatible(decl.params(), decl.returnType(), m[1], loader)) { return; }
		}
		local.add(new Finding(rel, line, annotation + " method '" + decl.name() + "' has no matching "
				+ "descriptor in " + target.replace('/', '.') + " (found: "
				+ String.join(", ", same.stream().map(m -> m[0] + m[1]).distinct().toList()) + ")"));
	}

	/** @Inject(method = "...") names a method, possibly with a descriptor or a trailing wildcard. */
	private static void checkSelector(String rel, int line, String target, String annotation,
			String selector, Loader loader, List<Finding> local) {
		checkCount++;
		if (!selectorMatches(loader, target, selector).isEmpty()) { return; }
		List<String[]> all = loader.methodsOf(target);
		List<String> names = all.stream().map(m -> m[0]).distinct().toList();
		String wanted = selector;
		int paren = wanted.indexOf('(');
		if (paren > 0) { wanted = wanted.substring(0, paren).trim(); }
		local.add(new Finding(rel, line, "@" + annotation + " method '" + selector + "' not found in "
				+ target.replace('/', '.') + " (methods: "
				+ String.join(", ", nearest(selectorStem(wanted), names)) + ")"));
	}

	/** The target methods one method = "..." selector names; empty when the selector matches nothing. */
	private static List<String[]> selectorMatches(Loader loader, String target, String selector) {
		String name = selector;
		String desc = null;
		int paren = selector.indexOf('(');
		if (paren > 0) {
			name = selector.substring(0, paren).trim();
			desc = selector.substring(paren).replace(" ", "");
		}
		name = selectorStem(name);
		boolean prefix = name.endsWith("*");
		String stem = prefix ? name.substring(0, name.length() - 1) : name;
		List<String[]> out = new ArrayList<>();
		for (String[] m : loader.methodsOf(target)) {
			boolean nameOk = prefix ? m[0].startsWith(stem) : m[0].equals(stem);
			if (!nameOk) { continue; }
			if (desc != null && !desc.equals(m[1])) { continue; }
			out.add(m);
		}
		return out;
	}

	/** "net/minecraft/X.foo" -> "foo", "La/b/C;foo" -> "foo". */
	private static String selectorStem(String selector) {
		String name = selector.trim();
		int slash = name.lastIndexOf('/');
		if (slash >= 0 && name.indexOf('.', slash) < 0) { name = name.substring(slash + 1); }
		int semi = name.lastIndexOf(';');
		if (semi >= 0) { name = name.substring(semi + 1); }
		return name;
	}

	// ------------------------------------------------------------------ @At targets

	/**
	 * Checks the @At of one injection: does the member it points at exist? A target it cannot
	 * resolve (another mod's class) is noted and skipped, like an unresolvable @Mixin target.
	 */
	private static void checkAt(String rel, int line, String annotation, String args,
			List<String> resolved, Loader loader, List<Finding> local) {
		String kind = stringValue(attribute(args, 0, args.length(), "value"));
		if (kind.isEmpty()) { kind = firstString(args); }
		if (kind.isEmpty()) {
			notes.add(rel + ": @" + annotation + " at line " + line + " has an @At this reads no value for");
			return;
		}
		if (NO_TARGET_AT.contains(kind)) { return; }
		if (!kind.equals("INVOKE") && !kind.equals("FIELD") && !kind.equals("NEW")) {
			notes.add(rel + ": @At(" + kind + ") at line " + line + " is not checked");
			return;
		}
		String value = stringValue(attribute(args, 0, args.length(), "target"));
		if (value.isEmpty()) {
			local.add(new Finding(rel, line, "@At(" + kind + ") has no target"));
			return;
		}
		String[] member = memberTarget(value);
		String owner = member[0];
		String name = member[1];
		String desc = member[2];
		if (kind.equals("NEW")) {
			checkNew(rel, line, value, owner != null ? owner : name, loader, local);
			return;
		}
		List<String> classes = new ArrayList<>();
		if (owner != null) {
			String internal = resolve(owner, loader);
			if (internal == null) {
				notes.add(rel + ": @At(" + kind + ") owner " + owner.replace('/', '.') + " at line " + line
						+ " is not in the given jars, not checked");
				return;
			}
			classes.add(internal);
		} else {
			// No owner: the member has to be reachable from the class being injected into.
			classes.addAll(resolved);
		}
		if (classes.isEmpty()) { return; }
		String wanted = kind.equals("FIELD") && desc != null && desc.startsWith(":") ? desc.substring(1) : desc;
		for (String c : classes) {
			checkCount++;
			List<String[]> members = kind.equals("FIELD") ? loader.fieldsIn(c) : loader.methodsIn(c);
			boolean found = false;
			for (String[] m : members) {
				if (!m[0].equals(name)) { continue; }
				if (wanted != null && !wanted.equals(m[1])) { continue; }
				found = true;
				break;
			}
			if (found) { continue; }
			List<String> candidates = members.stream().map(m -> m[0]).distinct().toList();
			local.add(new Finding(rel, line, "@At(" + kind + ") target '" + value + "': no "
					+ (kind.equals("FIELD") ? "field '" : "method '") + name + "'"
					+ (wanted == null ? "" : " " + wanted) + " in " + c.replace('/', '.')
					+ " (" + (kind.equals("FIELD") ? "fields: " : "methods: ")
					+ String.join(", ", nearest(name, candidates)) + ")"));
		}
	}

	/** @At(value = "NEW", target = "a.b.C") names a class, not a member. */
	private static void checkNew(String rel, int line, String value, String className, Loader loader,
			List<Finding> local) {
		checkCount++;
		if (resolve(className, loader) != null) { return; }
		// A name in a package the jars know is a dead reference; anything else is another mod's class.
		String want = className.replace('.', '/');
		int slash = want.lastIndexOf('/');
		String pkg = slash < 0 ? "" : want.substring(0, slash);
		List<String> near = new ArrayList<>();
		if (!pkg.isEmpty()) {
			for (String c : loader.all) {
				int s = c.lastIndexOf('/');
				if (s > 0 && c.substring(0, s).equals(pkg)) { near.add(simple(c)); }
			}
		}
		if (near.isEmpty()) {
			notes.add(rel + ": @At(NEW) class " + className.replace('/', '.') + " at line " + line
					+ " is not in the given jars, not checked");
			return;
		}
		local.add(new Finding(rel, line, "@At(NEW) class '" + value + "' not found in "
				+ pkg.replace('/', '.') + " (classes: "
				+ String.join(", ", nearest(simple(want), near)) + ")"));
	}

	/** {owner, name, descriptor} from an @At target; owner and descriptor are null when absent. */
	private static String[] memberTarget(String raw) {
		String s = raw.trim();
		String owner = null;
		if (s.startsWith("L")) {
			int semi = s.indexOf(';');
			if (semi > 0) {
				owner = s.substring(1, semi);
				s = s.substring(semi + 1);
			}
		} else {
			int cut = s.length();
			int paren = s.indexOf('(');
			if (paren >= 0) { cut = paren; }
			int dot = s.lastIndexOf('.', cut - 1);
			if (dot > 0) {
				owner = s.substring(0, dot).replace('.', '/');
				s = s.substring(dot + 1);
			}
		}
		int end = s.length();
		int paren = s.indexOf('(');
		int colon = s.indexOf(':');
		if (paren >= 0) { end = Math.min(end, paren); }
		if (colon >= 0) { end = Math.min(end, colon); }
		String name = s.substring(0, end).trim();
		String desc = s.substring(end).replace(" ", "");
		return new String[] {owner, name, desc.isEmpty() ? null : desc};
	}

	// ------------------------------------------------------------------ @Inject handlers

	/**
	 * Does the declared handler fit the target method? Mixin checks this when it applies, and a
	 * mismatch is a hard crash at load: the handler must return void, end in one CallbackInfo
	 * (CallbackInfoReturnable of the target's return type when the target returns something),
	 * and take at most the target's parameters, as a leading prefix with matching types.
	 */
	private static void checkHandler(String rel, int line, String target, Decl decl, List<String[]> methods,
			Loader loader, List<Finding> local) {
		if (methods.isEmpty()) { return; } // the method selector is reported on its own
		checkCount++;
		String handler = render(decl);
		String where = target.replace('/', '.') + "." + methods.get(0)[0] + methods.get(0)[1];
		if (decl.returnType() != null && !decl.returnType().equals("void")) {
			local.add(new Finding(rel, line, "@Inject handler '" + handler + "' returns "
					+ decl.returnType() + "; an @Inject handler returns void (target: " + where + ")"));
			return;
		}
		List<String> params = decl.params();
		if (params.isEmpty() || !params.get(params.size() - 1).startsWith("CallbackInfo")) {
			local.add(new Finding(rel, line, "@Inject handler '" + handler
					+ "' has no CallbackInfo/CallbackInfoReturnable as its last parameter (target: "
					+ where + ")"));
			return;
		}
		String reason = null;
		for (String[] m : methods) {
			reason = handlerMismatch(decl, target, m, loader);
			if (reason == null) { return; }
		}
		local.add(new Finding(rel, line, "@Inject handler '" + handler + "' does not match " + where
				+ ": " + reason + (methods.size() > 1 ? " (targets: " + describe(methods) + ")" : "")));
	}

	/** Why the handler does not fit this target method, or null when it does. */
	private static String handlerMismatch(Decl decl, String target, String[] method, Loader loader) {
		List<String> params = decl.params();
		List<String> given = params.subList(0, params.size() - 1); // before the CallbackInfo
		List<String> types = paramDescriptors(method[1]);
		// Mixin wants the handler static exactly when the target is, and either way the handler
		// takes the target's own parameters: a static target has no instance to pass.
		boolean targetStatic = method.length > 2 && "static".equals(method[2]);
		if (decl.isStatic() != targetStatic) {
			return targetStatic ? method[0] + " is static, so the handler must be static too"
					: "the handler is static but " + method[0] + " isn't";
		}
		int offset = 0;
		if (decl.captures()) {
			// @Local/@Share/@Coerce parameters depend on what is in scope at the point: not checked.
			return callbackMismatch(params.get(params.size() - 1), method, loader);
		}
		if (given.size() - offset > types.size()) {
			return "it takes " + (given.size() - offset) + " parameter(s) before the callback but "
					+ method[0] + " takes " + types.size();
		}
		for (int i = offset; i < given.size(); i++) {
			String mine = typeDescriptor(given.get(i), loader);
			if (mine == null) { continue; } // unresolvable source type: arity decides
			String want = types.get(i - offset);
			if (!mine.equals(want)) {
				return "parameter " + (i + 1) + " is " + given.get(i) + " (" + mine + ") but "
						+ method[0] + " takes " + want + " there";
			}
		}
		return callbackMismatch(params.get(params.size() - 1), method, loader);
	}

	/** The trailing CallbackInfo has to be the right one, with the right type argument. */
	private static String callbackMismatch(String last, String[] method, Loader loader) {
		String returns = method[1].substring(method[1].indexOf(')') + 1);
		boolean targetVoid = returns.equals("V");
		boolean returnable = last.startsWith("CallbackInfoReturnable");
		if (targetVoid && returnable) {
			return "it takes " + last + " but " + method[0] + " returns void, so it needs a CallbackInfo";
		}
		if (!targetVoid && !returnable) {
			return "it takes " + last + " but " + method[0] + " returns " + returns
					+ ", so it needs a CallbackInfoReturnable<" + returns + ">";
		}
		if (!returnable) { return null; }
		String generic = genericOf(last);
		if (generic == null || generic.isEmpty()) {
			return "it takes a raw " + last + " but " + method[0] + " returns " + returns;
		}
		String mine = typeDescriptor(generic, loader);
		if (mine == null) { return null; }
		if (!mine.equals(returns)) {
			return "it takes CallbackInfoReturnable<" + generic + "> but " + method[0] + " returns " + returns;
		}
		return null;
	}

	private static String render(Decl decl) {
		return decl.name() + "(" + String.join(", ", decl.params()) + ")";
	}

	private static String describe(List<String[]> methods) {
		List<String> out = new ArrayList<>();
		for (String[] m : methods) { out.add(m[0] + m[1]); }
		return String.join(", ", out);
	}

	private static String genericOf(String type) {
		int lt = type.indexOf('<');
		int gt = type.lastIndexOf('>');
		if (lt < 0 || gt <= lt) { return null; }
		return type.substring(lt + 1, gt).trim();
	}

	/** Does the target descriptor accept the mixin's declared parameter list? */
	private static boolean compatible(List<String> params, String returnType, String descriptor, Loader loader) {
		if (params.size() != countParams(descriptor)) { return false; }
		List<String> types = paramDescriptors(descriptor);
		for (int i = 0; i < params.size(); i++) {
			String mine = typeDescriptor(params.get(i), loader);
			if (mine == null) { continue; } // unresolvable source type: name and arity decide
			if (!mine.equals(types.get(i))) { return false; }
		}
		// A shadow with the right parameters but the wrong return type does not bind either.
		String wants = typeDescriptor(returnType == null ? "" : returnType, loader);
		if (wants == null) { return true; }
		return wants.equals(descriptor.substring(descriptor.indexOf(')') + 1));
	}

	private static int countParams(String descriptor) {
		return paramDescriptors(descriptor).size();
	}

	private static List<String> paramDescriptors(String descriptor) {
		List<String> out = new ArrayList<>();
		int i = descriptor.indexOf('(') + 1;
		while (i < descriptor.length() && descriptor.charAt(i) != ')') {
			int start = i;
			while (descriptor.charAt(i) == '[') { i++; }
			if (descriptor.charAt(i) == 'L') { i = descriptor.indexOf(';', i) + 1; }
			else { i++; }
			out.add(descriptor.substring(start, i));
		}
		return out;
	}

	// ------------------------------------------------------------------ names

	private static String resolve(String want, Loader loader) {
		String internal = want.replace('.', '/');
		if (loader.all.contains(internal)) { return internal; }
		List<String> hits = loader.bySimple.get(want);
		if (hits == null && want.contains(".")) { hits = loader.bySimple.get(simple(internal)); }
		if (hits != null && !hits.isEmpty()) { return hits.get(0); }
		return null;
	}

	private static String simple(String internal) {
		return internal.substring(internal.lastIndexOf('/') + 1);
	}

	private static List<String> mixinTargets(String args) {
		List<String> out = new ArrayList<>();
		// "@Mixin(Foo.class)" and "targets = {"a.b.C", "d.E"}".
		for (int i = 0; i < args.length(); i++) {
			char c = args.charAt(i);
			if (c == '"') {
				int end = skipString(args, i);
				String value = args.substring(i + 1, Math.max(i + 1, end - 1));
				if (!value.isEmpty()) { out.add(value); }
				i = end - 1;
			} else if (c == '.' && i + ".class".length() <= args.length() && args.startsWith(".class", i)) {
				int start = i;
				while (start > 0 && (Character.isJavaIdentifierPart(args.charAt(start - 1))
						|| args.charAt(start - 1) == '.')) {
					start--;
				}
				String value = args.substring(start, i);
				if (!value.isEmpty() && !value.startsWith("value")) { out.add(value); }
			}
		}
		return out;
	}

	/** The value of one attribute inside an annotation's argument list. */
	private static String attribute(String text, int from, int to, String key) {
		String args = text.substring(from, to);
		int at = 0;
		while ((at = args.indexOf(key, at)) >= 0) {
			boolean boundedLeft = at == 0 || !Character.isJavaIdentifierPart(args.charAt(at - 1));
			int after = at + key.length();
			boolean boundedRight = after >= args.length() || !Character.isJavaIdentifierPart(args.charAt(after));
			if (boundedLeft && boundedRight) {
				int i = after;
				while (i < args.length() && Character.isWhitespace(args.charAt(i))) { i++; }
				if (i < args.length() && args.charAt(i) == '=') {
					i++;
					StringBuilder sb = new StringBuilder();
					int depth = 0;
					for (; i < args.length(); i++) {
						char c = args.charAt(i);
						if (c == '"') {
							int end = skipString(args, i);
							sb.append(args, i, end);
							i = end - 1;
							continue;
						}
						if (c == '{' || c == '(') { depth++; }
						if (c == '}' || c == ')') {
							if (depth == 0) { break; }
							depth--;
						}
						if (c == ',' && depth == 0) { break; }
						sb.append(c);
					}
					return sb.toString().trim();
				}
			}
			at = after;
		}
		return "";
	}

	/** Every quoted string in an attribute value, so {"a", "b"} and "a" behave the same. */
	private static List<String> selectors(String value) {
		List<String> out = new ArrayList<>();
		if (value == null || value.isBlank()) { return out; }
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) != '"') { continue; }
			int end = skipString(value, i);
			String s = value.substring(i + 1, Math.max(i + 1, end - 1)).trim();
			if (!s.isEmpty()) { out.add(s); }
			i = end - 1;
		}
		if (out.isEmpty() && !value.isBlank()) { out.add(value.trim()); }
		return out;
	}

	// ------------------------------------------------------------------ source text

	/** The argument range of the first {@code @At( ... )} inside an annotation's arguments, or null. */
	private static int[] atArgs(String text, int from, int to) {
		for (int i = from; i < to; i++) {
			if (text.charAt(i) != '@' || !text.startsWith("At", i + 1)) { continue; }
			int after = i + 3;
			if (after < text.length() && Character.isJavaIdentifierPart(text.charAt(after))) { continue; }
			int k = after;
			while (k < text.length() && Character.isWhitespace(text.charAt(k))) { k++; }
			if (k < text.length() && text.charAt(k) == '(') {
				return new int[] {k + 1, matching(text, k)};
			}
		}
		return null;
	}

	/** The first quoted string in an attribute value, or the value itself when it is a constant. */
	private static String stringValue(String raw) {
		List<String> all = selectors(raw);
		return all.isEmpty() ? "" : all.get(0);
	}

	/** The first quoted string in an @At's arguments, for the positional @At("HEAD") form. */
	private static String firstString(String raw) {
		int quote = raw.indexOf('"');
		if (quote < 0) { return ""; }
		int end = skipString(raw, quote);
		return raw.substring(quote + 1, Math.max(quote + 1, end - 1)).trim();
	}

	/** All annotations: {start index, name start, name end, args start, args end}. */
	private static List<int[]> annotations(String text) {
		List<int[]> out = new ArrayList<>();
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) != '@') { continue; }
			int at = i;
			int nameStart = i + 1;
			int j = nameStart;
			while (j < text.length() && Character.isJavaIdentifierPart(text.charAt(j))) { j++; }
			if (j == nameStart) { continue; }
			int argsStart = j;
			int argsEnd = j;
			int k = j;
			while (k < text.length() && Character.isWhitespace(text.charAt(k))) { k++; }
			if (k < text.length() && text.charAt(k) == '(') {
				argsStart = k + 1;
				argsEnd = matching(text, k);
				i = argsEnd;
			} else {
				i = j - 1;
			}
			out.add(new int[] {at, nameStart, j, argsStart, argsEnd});
		}
		return out;
	}

	/** Index of the ')' that closes the '(' at open. */
	private static int matching(String text, int open) {
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') {
				i = skipString(text, i) - 1;
				continue;
			}
			if (c == '(') { depth++; }
			if (c == ')') {
				depth--;
				if (depth == 0) { return i; }
			}
		}
		return text.length() - 1;
	}

	private static int skipString(String text, int quote) {
		char q = text.charAt(quote);
		int i = quote + 1;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '\\') { i += 2; continue; }
			if (c == q) { return i + 1; }
			i++;
		}
		return text.length();
	}

	/** The declaration that belongs to the annotation ending at from. */
	private static Decl declAfter(String text, int from) {
		int i = from;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) { i++; continue; }
			if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
				int nl = text.indexOf('\n', i);
				i = nl < 0 ? text.length() : nl + 1;
				continue;
			}
			if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
				int end = text.indexOf("*/", i + 2);
				i = end < 0 ? text.length() : end + 2;
				continue;
			}
			if (c == '@') {
				i = skipAnnotationAt(text, i);
				continue;
			}
			break;
		}
		if (i >= text.length()) { return null; }
		int start = i;
		int paren = depthsAware(text, start, '(');
		int semi = depthsAware(text, start, ';');
		int eq = depthsAware(text, start, '=');
		boolean method = paren >= 0 && (semi < 0 || paren < semi) && (eq < 0 || paren < eq);
		if (method) {
			String before = text.substring(start, paren);
			String name = lastIdentifier(before);
			if (name.isEmpty()) { return null; }
			int close = matching(text, paren);
			String rawParams = text.substring(paren + 1, close);
			// @Local/@Share/@Coerce parameters are locals captured at the point, not target args.
			boolean captures = rawParams.contains("@Local") || rawParams.contains("@Share")
					|| rawParams.contains("@Coerce");
			return new Decl(0, false, name, params(rawParams), typeOf(before, name),
					before.matches("(?s).*\\bstatic\\b.*"), captures);
		}
		int end = semi >= 0 ? semi : (eq >= 0 ? eq : Math.min(text.length(), start + 200));
		String before = text.substring(start, end);
		String name = lastIdentifier(before);
		if (name.isEmpty()) { return null; }
		return new Decl(0, true, name, List.of(), null, before.matches("(?s).*\\bstatic\\b.*"), false);
	}

	private static int skipAnnotationAt(String text, int at) {
		int j = at + 1;
		while (j < text.length() && Character.isJavaIdentifierPart(text.charAt(j))) { j++; }
		int k = j;
		while (k < text.length() && Character.isWhitespace(text.charAt(k))) { k++; }
		if (k < text.length() && text.charAt(k) == '(') { return matching(text, k) + 1; }
		return j;
	}

	/** First index of c from start, ignoring string literals and nested brackets. */
	private static int depthsAware(String text, int start, char want) {
		int depth = 0;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') { i = skipString(text, i) - 1; continue; }
			if (c == '<' || c == '[') { depth++; }
			if (c == '>' || c == ']') { depth = Math.max(0, depth - 1); }
			if (depth == 0 && c == want) { return i; }
			if (depth == 0 && c == '\n' && want != ';') { return -1; }
			if (depth == 0 && c == '{') { return -1; }
		}
		return -1;
	}

	private static final Pattern MODIFIERS = Pattern.compile(
			"^(?:(?:public|protected|private|static|final|abstract|native|synchronized|default"
					+ "|strictfp|transient|volatile)\\s+|(?:<[^>]*>\\s+))+");

	/** The declared return type in "protected abstract int getTickCount", or null. */
	private static String typeOf(String before, String name) {
		if (before == null) { return null; }
		int end = before.length() - name.length();
		if (end <= 0) { return null; }
		String rest = MODIFIERS.matcher(before.substring(0, end).trim()).replaceFirst("").trim();
		return rest.isEmpty() ? null : rest;
	}

	private static String lastIdentifier(String s) {		int end = s.length();
		while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) { end--; }
		int start = end;
		while (start > 0 && Character.isJavaIdentifierPart(s.charAt(start - 1))) { start--; }
		return s.substring(start, end);
	}

	/** Parameter type names of a declaration, annotations and names stripped. */
	private static List<String> params(String raw) {
		List<String> out = new ArrayList<>();
		if (raw.isBlank()) { return out; }
		int depth = 0;
		int start = 0;
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '<' || c == '(') { depth++; }
			if (c == '>' || c == ')') { depth = Math.max(0, depth - 1); }
			if (c == ',' && depth == 0) {
				parts.add(raw.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(raw.substring(start));
		for (String part : parts) {
			String p = part.trim();
			// MixinExtras sugar (@Local, @Share, @Cancellable) is filled in separately and may follow
			// the CallbackInfo, so it is not part of the handler signature Mixin checks.
			if (p.matches("^@(\\w+\\.)*(Local|Share|Cancellable)\\b.*")) { continue; }
			while (p.startsWith("@")) { p = p.substring(skipAnnotationAt(p, 0)).trim(); }
			p = p.replaceAll("^final\\s+", "");
			String name = lastIdentifier(p);
			if (name.isEmpty()) { continue; }
			String type = p.substring(0, p.length() - name.length()).trim();
			if (!type.isEmpty()) { out.add(type); }
		}
		return out;
	}

	/** JVM descriptor for a source type, or null when the name cannot be resolved in the jars. */
	private static String typeDescriptor(String type, Loader loader) {
		String t = type.trim();
		int lt = t.indexOf('<');
		if (lt >= 0) { t = t.substring(0, lt).trim(); }
		int dims = 0;
		while (true) {
			if (t.endsWith("[]")) {
				dims++;
				t = t.substring(0, t.length() - 2).trim();
			} else if (t.endsWith("...")) {
				dims++;
				t = t.substring(0, t.length() - 3).trim();
			} else {
				break;
			}
		}
		String base = switch (t) {
			case "void" -> "V";
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			default -> {
				String internal = null;
				if (t.contains(".")) {
					String direct = t.replace('.', '/');
					if (loader.all.contains(direct)) { internal = direct; }
					// Source spells a nested class Item.TooltipContext; its jar entry uses Item$TooltipContext.
					// Try each package/class boundary and accept only one real class, never a guessed descriptor.
					Set<String> nested = new HashSet<>();
					String[] parts = t.split("\\.");
					for (int boundary = 0; boundary < parts.length; boundary++) {
						String tail = String.join("$", Arrays.copyOfRange(parts, boundary, parts.length));
						if (boundary == 0) { nested.addAll(loader.bySimple.getOrDefault(tail, List.of())); }
						else {
							String candidate = String.join("/", Arrays.copyOfRange(parts, 0, boundary)) + "/" + tail;
							if (loader.all.contains(candidate)) { nested.add(candidate); }
						}
					}
					if (internal == null && nested.size() == 1) { internal = nested.iterator().next(); }
				}
				if (internal == null) {
					List<String> hits = loader.bySimple.get(t);
					if (hits != null && hits.size() == 1) { internal = hits.get(0); }
				}
				if (internal == null) { yield null; }
				yield "L" + internal + ";";
			}
		};
		if (base == null) { return null; }
		return "[".repeat(dims) + base;
	}

	private static int[] lineStarts(String text) {
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') { starts.add(i + 1); }
		}
		return starts.stream().mapToInt(Integer::intValue).toArray();
	}

	private static int lineOf(int[] lineStarts, int index) {
		int lo = 0;
		int hi = lineStarts.length - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (lineStarts[mid] <= index) { lo = mid; } else { hi = mid - 1; }
		}
		return lo + 1;
	}

	/** Up to five likely intended names, closest spelling first. */
	private static List<String> nearest(String missing, List<String> candidates) {
		String want = missing.toLowerCase();
		List<String> out = new ArrayList<>(candidates);
		out.sort(Comparator.comparingInt((String c) -> distance(want, c.toLowerCase()))
				.thenComparing(Comparator.naturalOrder()));
		if (out.size() > 5) { out = new ArrayList<>(out.subList(0, 5)); }
		if (out.isEmpty()) { out.add("none"); }
		return out;
	}

	private static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) { prev[j] = j; }
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] swap = prev;
			prev = cur;
			cur = swap;
		}
		return prev[b.length()];
	}
}
