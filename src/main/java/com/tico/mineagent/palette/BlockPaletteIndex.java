package com.tico.mineagent.palette;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BlockPaletteIndex {
	private static final String RESOURCE_PATH = "data/mineagent/minecraft_block_color_palette.csv";
	private static volatile BlockPaletteIndex instance;

	private final List<String> headers;
	private final List<Entry> entries;

	private BlockPaletteIndex(List<String> headers, List<Entry> entries) {
		this.headers = List.copyOf(headers);
		this.entries = List.copyOf(entries);
	}

	public static BlockPaletteIndex load() throws IOException {
		BlockPaletteIndex current = instance;
		if (current != null) {
			return current;
		}

		synchronized (BlockPaletteIndex.class) {
			current = instance;
			if (current == null) {
				current = readResource();
				instance = current;
			}
			return current;
		}
	}

	public QueryResult query(String mode, String query, String column, int limit) {
		String normalizedMode = mode.toLowerCase(Locale.ROOT);
		String normalizedQuery = query.trim();
		int cappedLimit = Math.max(1, Math.min(limit, 16));
		List<String> warnings = new ArrayList<>();
		List<Match> matches = switch (normalizedMode) {
			case "block_id" -> queryBlockId(normalizedQuery);
			case "color" -> queryColor(normalizedQuery, warnings);
			case "column" -> queryColumn(normalizedQuery, column, warnings);
			case "text" -> queryText(normalizedQuery);
			default -> throw new IllegalArgumentException("Unsupported palette query mode. Use block_id, color, column, or text.");
		};

		return new QueryResult(normalizedMode, normalizedQuery, cappedLimit, entries.size(), headers, seriesMatches(matches, normalizedMode).stream()
				.limit(cappedLimit)
				.toList(), warnings);
	}

	private List<Match> queryBlockId(String query) {
		String needle = query.toLowerCase(Locale.ROOT);
		return entries.stream()
				.map(entry -> {
					String blockId = entry.value("block_id").toLowerCase(Locale.ROOT);
					if (blockId.equals(needle)) {
						return new Match(entry, 0.0D, "exact block_id match");
					}
					if (blockId.startsWith(needle)) {
						return new Match(entry, 1.0D, "block_id prefix match");
					}
					if (blockId.contains(needle)) {
						return new Match(entry, 2.0D, "block_id contains query");
					}
					return null;
				})
				.filter(match -> match != null)
				.sorted(matchComparator())
				.toList();
	}

	private List<Match> queryColor(String query, List<String> warnings) {
		Color target = parseColor(query);
		if (target == null) {
			warnings.add("Could not parse a color from query '" + query + "'. Use #rrggbb, r,g,b, or a basic color phrase such as warm gray, dark green, orange, tan, beige, or blue.");
			return queryText(query);
		}

		return entries.stream()
				.filter(entry -> entry.hasColor() && !ignoredForColorSearch(entry))
				.map(entry -> {
					double distance = distance(target, entry.color());
					return new Match(entry, distance, "RGB distance %.1f from %s".formatted(distance, target.hex()));
				})
				.sorted(matchComparator())
				.toList();
	}

	private List<SeriesMatch> seriesMatches(List<Match> matches, String mode) {
		Map<String, List<Match>> grouped = new LinkedHashMap<>();
		for (Match match : matches) {
			grouped.computeIfAbsent(seriesId(match.entry()), ignored -> new ArrayList<>()).add(match);
		}

		List<SeriesMatch> seriesMatches = new ArrayList<>();
		for (Map.Entry<String, List<Match>> group : grouped.entrySet()) {
			List<Entry> members = seriesMembers(group.getKey());
			Match best = group.getValue().stream()
					.min(Comparator.<Match>comparingDouble(match -> adjustedScore(match, mode))
							.thenComparing(match -> entryPriority(match.entry()))
							.thenComparing(match -> match.entry().value("block_id")))
					.orElseThrow();
			Entry representative = members.stream()
					.min(entryComparator())
					.orElse(best.entry());
			double score = adjustedScore(best, mode);
			String reason = best.reason() + "; grouped as " + seriesLabel(group.getKey());
			seriesMatches.add(new SeriesMatch(group.getKey(), seriesLabel(group.getKey()), representative, members, score, reason));
		}

		return seriesMatches.stream()
				.sorted(Comparator.comparingDouble(SeriesMatch::score)
						.thenComparing(match -> entryPriority(match.representative()))
						.thenComparing(SeriesMatch::seriesId))
				.toList();
	}

	private List<Entry> seriesMembers(String seriesId) {
		return entries.stream()
				.filter(entry -> seriesId(entry).equals(seriesId))
				.sorted(entryComparator())
				.toList();
	}

	private List<Match> queryColumn(String query, String rawColumn, List<String> warnings) {
		String column = normalizeColumn(rawColumn);
		if (column == null) {
			warnings.add("Unknown palette column '" + rawColumn + "'. Returned text search results instead.");
			return queryText(query);
		}

		String needle = query.toLowerCase(Locale.ROOT);
		return entries.stream()
				.map(entry -> {
					String value = entry.value(column).toLowerCase(Locale.ROOT);
					if (value.equals(needle)) {
						return new Match(entry, 0.0D, "exact " + column + " match");
					}
					if (value.startsWith(needle)) {
						return new Match(entry, 1.0D, column + " prefix match");
					}
					if (value.contains(needle)) {
						return new Match(entry, 2.0D, column + " contains query");
					}
					return null;
				})
				.filter(match -> match != null)
				.sorted(matchComparator())
				.toList();
	}

	private List<Match> queryText(String query) {
		List<String> tokens = tokens(query);
		return entries.stream()
				.map(entry -> {
					int matched = 0;
					for (String token : tokens) {
						if (entry.searchText().contains(token)) {
							matched++;
						}
					}
					if (matched == 0 || matched < tokens.size()) {
						return null;
					}
					double score = -matched;
					return new Match(entry, score, "matched " + matched + " text tokens");
				})
				.filter(match -> match != null)
				.sorted(matchComparator())
				.toList();
	}

	private String normalizeColumn(String rawColumn) {
		if (rawColumn == null || rawColumn.isBlank()) {
			return null;
		}
		String column = rawColumn.trim().toLowerCase(Locale.ROOT);
		for (String header : headers) {
			if (header.equalsIgnoreCase(column)) {
				return header;
			}
		}
		return null;
	}

	private static Comparator<Match> matchComparator() {
		return Comparator.comparingDouble(Match::score)
				.thenComparing(match -> match.entry().value("block_id"));
	}

	private static Comparator<Entry> entryComparator() {
		return Comparator.comparingInt(BlockPaletteIndex::entryPriority)
				.thenComparing(entry -> entry.value("block_id"));
	}

	private static double adjustedScore(Match match, String mode) {
		if (!"color".equals(mode)) {
			return match.score();
		}
		return match.score() + entryPriority(match.entry()) * 10.0D;
	}

	private static int entryPriority(Entry entry) {
		String modelCategory = entry.value("model_category").toLowerCase(Locale.ROOT);
		String shapeFamily = entry.value("shape_family").toLowerCase(Locale.ROOT);
		String supportClass = entry.value("support_class").toLowerCase(Locale.ROOT);
		if ("full_cube_same_faces".equals(modelCategory)) {
			return 0;
		}
		if ("full_cube_different_faces".equals(modelCategory)) {
			return 1;
		}
		if ("stairs".equals(modelCategory) || "slab".equals(modelCategory) || "stairs".equals(shapeFamily) || "slab".equals(shapeFamily)) {
			return 2;
		}
		if (supportClass.contains("self_supporting")) {
			return 3;
		}
		if (supportClass.contains("dependent")) {
			return 4;
		}
		return 5;
	}

	private static String seriesId(Entry entry) {
		String blockId = stripNamespace(entry.value("block_id").toLowerCase(Locale.ROOT));
		String woodType = woodType(blockId);
		if (woodType != null) {
			if (blockId.equals(woodType + "_log") || blockId.equals(woodType + "_wood") || blockId.equals(woodType + "_block")) {
				return woodType + "_log";
			}
			if (blockId.equals("stripped_" + woodType + "_log")
					|| blockId.equals("stripped_" + woodType + "_wood")
					|| blockId.equals("stripped_" + woodType + "_block")
					|| blockId.equals(woodType + "_planks")
					|| hasWoodPlankVariant(blockId, woodType)) {
				return woodType + "_planks";
			}
		}
		return stripSeriesSuffix(blockId);
	}

	private static String seriesLabel(String seriesId) {
		return seriesId.replace('_', ' ') + " series";
	}

	private static String stripNamespace(String blockId) {
		int colon = blockId.indexOf(':');
		return colon >= 0 ? blockId.substring(colon + 1) : blockId;
	}

	private static String stripSeriesSuffix(String blockId) {
		String[] suffixes = {
				"_wall_hanging_sign",
				"_hanging_sign",
				"_pressure_plate",
				"_fence_gate",
				"_wall_sign",
				"_trapdoor",
				"_stairs",
				"_button",
				"_fence",
				"_slab",
				"_wall",
				"_door",
				"_sign"
		};
		for (String suffix : suffixes) {
			if (blockId.endsWith(suffix) && blockId.length() > suffix.length()) {
				return blockId.substring(0, blockId.length() - suffix.length());
			}
		}
		return blockId;
	}

	private static String woodType(String blockId) {
		String[] woodTypes = {
				"dark_oak",
				"pale_oak",
				"spruce",
				"birch",
				"jungle",
				"acacia",
				"cherry",
				"mangrove",
				"bamboo",
				"crimson",
				"warped",
				"oak"
		};
		String stripped = blockId.startsWith("stripped_") ? blockId.substring("stripped_".length()) : blockId;
		for (String woodType : woodTypes) {
			if (stripped.equals(woodType)
					|| stripped.startsWith(woodType + "_")
					|| stripped.startsWith(woodType.replace("_", "") + "_")) {
				return woodType;
			}
		}
		return null;
	}

	private static boolean hasWoodPlankVariant(String blockId, String woodType) {
		String prefix = woodType + "_";
		return blockId.startsWith(prefix) && (
				blockId.endsWith("_stairs")
						|| blockId.endsWith("_slab")
						|| blockId.endsWith("_fence")
						|| blockId.endsWith("_fence_gate")
						|| blockId.endsWith("_door")
						|| blockId.endsWith("_trapdoor")
						|| blockId.endsWith("_button")
						|| blockId.endsWith("_pressure_plate")
						|| blockId.endsWith("_sign")
						|| blockId.endsWith("_hanging_sign")
						|| blockId.endsWith("_shelf")
						|| blockId.endsWith("_mosaic"));
	}

	private static boolean ignoredForColorSearch(Entry entry) {
		String blockId = stripNamespace(entry.value("block_id").toLowerCase(Locale.ROOT));
		return blockId.contains("chest")
				|| blockId.contains("shulker_box")
				|| blockId.endsWith("_bed")
				|| blockId.endsWith("_banner")
				|| blockId.endsWith("_sign")
				|| blockId.endsWith("_hanging_sign")
				|| blockId.endsWith("_skull")
				|| blockId.endsWith("_head")
				|| blockId.contains("flower_pot")
				|| blockId.contains("decorated_pot")
				|| blockId.equals("beacon")
				|| blockId.equals("conduit")
				|| blockId.contains("furnace")
				|| blockId.equals("smoker")
				|| blockId.equals("hopper")
				|| blockId.equals("dispenser")
				|| blockId.equals("dropper")
				|| blockId.equals("jukebox")
				|| blockId.equals("note_block")
				|| blockId.equals("lectern")
				|| blockId.contains("command_block")
				|| blockId.equals("structure_block")
				|| blockId.equals("jigsaw");
	}

	private static BlockPaletteIndex readResource() throws IOException {
		InputStream stream = BlockPaletteIndex.class.getClassLoader().getResourceAsStream(RESOURCE_PATH);
		if (stream == null) {
			throw new IOException("Missing MineAgent palette resource: " + RESOURCE_PATH);
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				throw new IOException("Empty MineAgent palette resource: " + RESOURCE_PATH);
			}

			List<String> headers = parseCsvLine(headerLine);
			List<Entry> entries = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				List<String> values = parseCsvLine(line);
				Map<String, String> fields = new HashMap<>();
				for (int i = 0; i < headers.size(); i++) {
					String value = i < values.size() ? values.get(i) : "";
					fields.put(headers.get(i), value);
				}
				entries.add(new Entry(fields, buildSearchText(fields), parseEntryColor(fields)));
			}
			return new BlockPaletteIndex(headers, entries);
		}
	}

	private static List<String> parseCsvLine(String line) {
		List<String> values = new ArrayList<>();
		StringBuilder value = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted) {
				if (c == '"') {
					if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
						value.append('"');
						i++;
					} else {
						quoted = false;
					}
				} else {
					value.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				values.add(value.toString());
				value.setLength(0);
			} else {
				value.append(c);
			}
		}
		values.add(value.toString());
		return values;
	}

	private static String buildSearchText(Map<String, String> fields) {
		StringBuilder text = new StringBuilder();
		for (String value : fields.values()) {
			if (!value.isBlank()) {
				text.append(value.toLowerCase(Locale.ROOT)).append(' ');
			}
		}
		return text.toString();
	}

	private static Color parseEntryColor(Map<String, String> fields) {
		Integer r = parseInt(fields.get("average_color_r"));
		Integer g = parseInt(fields.get("average_color_g"));
		Integer b = parseInt(fields.get("average_color_b"));
		if (r == null || g == null || b == null) {
			return null;
		}
		return new Color(r, g, b);
	}

	private static Integer parseInt(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static Color parseColor(String query) {
		String trimmed = query.trim().toLowerCase(Locale.ROOT);
		if (trimmed.matches("#?[0-9a-f]{6}")) {
			String hex = trimmed.charAt(0) == '#' ? trimmed.substring(1) : trimmed;
			return new Color(
					Integer.parseInt(hex.substring(0, 2), 16),
					Integer.parseInt(hex.substring(2, 4), 16),
					Integer.parseInt(hex.substring(4, 6), 16));
		}

		String numeric = trimmed.replace("rgb(", "").replace(")", "");
		String[] parts = numeric.split(",", -1);
		if (parts.length == 3) {
			Integer r = parseInt(parts[0]);
			Integer g = parseInt(parts[1]);
			Integer b = parseInt(parts[2]);
			if (r != null && g != null && b != null) {
				return new Color(clampColor(r), clampColor(g), clampColor(b));
			}
		}

		Color base = null;
		for (String token : tokens(trimmed.replace('_', ' '))) {
			Color named = namedColor(token);
			if (named != null) {
				base = base == null ? named : average(base, named);
			}
		}
		if (base == null) {
			return null;
		}
		if (trimmed.contains("dark") || trimmed.contains("deep")) {
			base = scale(base, 0.65D);
		}
		if (trimmed.contains("light") || trimmed.contains("pale")) {
			base = mix(base, new Color(245, 245, 235), 0.45D);
		}
		if (trimmed.contains("warm")) {
			base = mix(base, new Color(188, 116, 60), 0.25D);
		}
		if (trimmed.contains("cool")) {
			base = mix(base, new Color(80, 125, 170), 0.25D);
		}
		if (trimmed.contains("muted") || trimmed.contains("desaturated")) {
			base = mix(base, new Color(128, 128, 128), 0.35D);
		}
		return base;
	}

	private static Color namedColor(String token) {
		return switch (token) {
			case "white" -> new Color(235, 235, 225);
			case "black" -> new Color(20, 20, 22);
			case "gray", "grey" -> new Color(125, 125, 120);
			case "red" -> new Color(155, 45, 38);
			case "orange" -> new Color(190, 92, 38);
			case "yellow" -> new Color(205, 170, 55);
			case "gold" -> new Color(215, 155, 45);
			case "green" -> new Color(70, 125, 55);
			case "lime" -> new Color(120, 185, 65);
			case "cyan", "teal" -> new Color(55, 150, 160);
			case "blue" -> new Color(55, 80, 165);
			case "purple" -> new Color(115, 65, 150);
			case "magenta" -> new Color(165, 65, 155);
			case "pink" -> new Color(205, 125, 150);
			case "brown" -> new Color(105, 70, 45);
			case "tan", "beige" -> new Color(180, 150, 105);
			case "cream" -> new Color(215, 200, 155);
			default -> null;
		};
	}

	private static List<String> tokens(String text) {
		List<String> tokens = new ArrayList<>();
		for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9:_#]+")) {
			if (!token.isBlank()) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	private static double distance(Color a, Color b) {
		int dr = a.r() - b.r();
		int dg = a.g() - b.g();
		int db = a.b() - b.b();
		return Math.sqrt(dr * dr + dg * dg + db * db);
	}

	private static Color average(Color a, Color b) {
		return new Color((a.r() + b.r()) / 2, (a.g() + b.g()) / 2, (a.b() + b.b()) / 2);
	}

	private static Color scale(Color color, double scale) {
		return new Color(clampColor((int) Math.round(color.r() * scale)), clampColor((int) Math.round(color.g() * scale)), clampColor((int) Math.round(color.b() * scale)));
	}

	private static Color mix(Color a, Color b, double amountB) {
		double amountA = 1.0D - amountB;
		return new Color(
				clampColor((int) Math.round(a.r() * amountA + b.r() * amountB)),
				clampColor((int) Math.round(a.g() * amountA + b.g() * amountB)),
				clampColor((int) Math.round(a.b() * amountA + b.b() * amountB)));
	}

	private static int clampColor(int value) {
		return Math.max(0, Math.min(255, value));
	}

	public List<String> headers() {
		return headers;
	}

	public record QueryResult(String mode, String query, int limit, int totalRows, List<String> headers, List<SeriesMatch> matches, List<String> warnings) {
	}

	public record Match(Entry entry, double score, String reason) {
	}

	public record SeriesMatch(String seriesId, String seriesLabel, Entry representative, List<Entry> members, double score, String reason) {
	}

	public record Entry(Map<String, String> fields, String searchText, Color color) {
		public String value(String column) {
			return fields.getOrDefault(column, "");
		}

		public boolean hasColor() {
			return color != null;
		}
	}

	public record Color(int r, int g, int b) {
		public String hex() {
			return "#%02X%02X%02X".formatted(r, g, b);
		}
	}
}
