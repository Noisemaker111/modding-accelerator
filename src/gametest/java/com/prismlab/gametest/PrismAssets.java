package com.prismlab.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Registration check that runs inside the live server: every block and item in the registry under
 * this mod's id must also ship the files a player needs, looked up in the mod's own resources the
 * same way the game looks them up (so dev folders and a built jar both work).
 *
 * The server never loads client assets, so this cannot ask the client model loader whether it
 * accepted a model; `look` is the tier that proves a model actually renders.
 */
final class PrismAssets {
	// A class only this mod ships: it tells our mod container apart from the gametest container
	// and from every library on the classpath.
	private static final String OWN_CLASS = "com/prismlab/PrismLab.class";

	private PrismAssets() {
	}

	/** Every way a registered block or item is broken, one line per problem with its path. */
	static List<String> problems() {
		String namespace = modId();
		List<String> problems = new ArrayList<>();
		JsonObject lang = lang(namespace, problems);

		List<Identifier> blocks = ourIds(BuiltInRegistries.BLOCK.keySet(), namespace);
		List<Identifier> items = ourIds(BuiltInRegistries.ITEM.keySet(), namespace);
		if (blocks.isEmpty() || items.isEmpty()) {
			problems.add("the live registry has " + blocks.size() + " blocks and " + items.size() + " items under "
					+ namespace + ": the mod did not register at all");
			return problems;
		}

		for (Identifier id : blocks) {
			String owner = "block " + id;
			require(problems, namespace, "blockstates/" + id.getPath() + ".json", owner);
			if (BuiltInRegistries.ITEM.containsKey(id)) {
				requireItemModel(problems, id, owner);
			}
			requireLang(problems, lang, namespace, "block." + namespace + "." + id.getPath(), owner);
		}

		for (Identifier id : items) {
			String owner = "item " + id;
			requireItemModel(problems, id, owner);
			// A block item is named by its block's key, which the block loop already checks.
			if (!BuiltInRegistries.BLOCK.containsKey(id)) {
				requireLang(problems, lang, namespace, "item." + namespace + "." + id.getPath(), owner);
			}
		}

		return problems;
	}

	/** The mod id as fabric.mod.json declares it, found from the jar or dev folder that holds us. */
	static String modId() {
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			boolean ours = mod.getRootPaths().stream().anyMatch(root -> Files.exists(root.resolve(OWN_CLASS)));
			if (!ours) {
				continue;
			}
			for (Path root : mod.getRootPaths()) {
				Path manifest = root.resolve("fabric.mod.json");
				if (!Files.isRegularFile(manifest)) {
					continue;
				}
				try (Reader reader = Files.newBufferedReader(manifest)) {
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					if (!json.has("id")) {
						throw new IllegalStateException(manifest + " has no id");
					}
					return json.get("id").getAsString();
				} catch (IOException e) {
					throw new IllegalStateException("cannot read " + manifest + ": " + e, e);
				}
			}
		}
		throw new IllegalStateException("no mod on the classpath holds " + OWN_CLASS + " and a fabric.mod.json");
	}

	private static List<Identifier> ourIds(java.util.Set<Identifier> keys, String namespace) {
		List<Identifier> ids = new ArrayList<>();
		for (Identifier id : keys) {
			if (id.getNamespace().equals(namespace)) {
				ids.add(id);
			}
		}
		ids.sort(Comparator.comparing(Identifier::toString));
		return ids;
	}

	/** An item needs assets/modid/items/id.json or, before 1.21.4, models/item/id.json. */
	private static void requireItemModel(List<String> problems, Identifier id, String owner) {
		String definition = "items/" + id.getPath() + ".json";
		String model = "models/item/" + id.getPath() + ".json";
		if (exists(id.getNamespace(), definition) || exists(id.getNamespace(), model)) {
			return;
		}
		problems.add("missing assets/" + id.getNamespace() + "/" + definition + " (or " + model + ") for " + owner);
	}

	private static void require(List<String> problems, String namespace, String path, String owner) {
		if (!exists(namespace, path)) {
			problems.add("missing assets/" + namespace + "/" + path + " for " + owner);
		}
	}

	private static void requireLang(List<String> problems, JsonObject lang, String namespace, String key, String owner) {
		if (lang == null) {
			return; // the missing lang file is already one problem, not one per id
		}
		if (!lang.has(key)) {
			problems.add("missing lang key " + key + " in assets/" + namespace + "/lang/en_us.json for " + owner);
		}
	}

	/** The resource the game would load for a mod asset path, wherever this mod came from. */
	private static InputStream open(String namespace, String path) {
		return PrismAssets.class.getResourceAsStream("/assets/" + namespace + "/" + path);
	}

	private static boolean exists(String namespace, String path) {
		InputStream in = open(namespace, path);
		if (in == null) {
			return false;
		}
		try {
			in.close();
		} catch (IOException ignored) {
			// the file is there; failing to close it says nothing about the asset
		}
		return true;
	}

	private static JsonObject lang(String namespace, List<String> problems) {
		String path = "lang/en_us.json";
		InputStream in = open(namespace, path);
		if (in == null) {
			problems.add("missing assets/" + namespace + "/" + path);
			return null;
		}
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			problems.add("cannot read assets/" + namespace + "/" + path + ": " + e);
			return null;
		}
	}
}
