package com.tico.mineagent.design;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.project.MineAgentProjectStore;
import com.tico.mineagent.sandbox.SandboxSession;

public final class DesignDocumentStore {
	public static final int MAX_FEATURES = 64;
	public static final int MAX_IMAGES = 128;
	private static final int MAX_MARKDOWN_CHARS = 64_000;
	private static final int MAX_IMAGE_DIMENSION = 2048;
	private static final int MAX_IMAGES_PER_WRITE = 8;
	private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;

	private DesignDocumentStore() {
	}

	public static JsonObject write(ServerPlayer player, SandboxSession sandbox, String document, String featureId, String content, JsonArray images) throws IOException {
		ResolvedDocument resolved = resolveDocument(player, sandbox, document, featureId, true);
		String markdown = safeMarkdown(content);
		Files.createDirectories(resolved.path().getParent());
		if (resolved.feature() && !Files.isRegularFile(resolved.path()) && featureCount(resolved.root()) >= MAX_FEATURES) {
			throw new IllegalStateException("MineAgent design docs already contain " + MAX_FEATURES + " feature files. Compress/rewrite existing feature docs before adding more.");
		}

		int incomingImages = images == null || images.isJsonNull() ? 0 : images.size();
		int currentImages = imageCount(resolved.root());
		if (incomingImages > 0 && currentImages + incomingImages > MAX_IMAGES) {
			throw new IllegalStateException("MineAgent design docs already contain " + currentImages + " image(s). Adding " + incomingImages + " would exceed the " + MAX_IMAGES + " image limit. Delete unnecessary design images first.");
		}

		ImageWriteResult imageResult = writeImages(resolved.root(), resolved.id(), images);
		String finalMarkdown = markdown;
		if (!imageResult.markdownLinks().isEmpty()) {
			finalMarkdown = finalMarkdown + "\n\n" + String.join("\n", imageResult.markdownLinks()) + "\n";
		}
		Files.writeString(resolved.path(), finalMarkdown, StandardCharsets.UTF_8);

		JsonObject object = baseResult(resolved);
		object.addProperty("written", true);
		object.addProperty("chars", finalMarkdown.length());
		object.addProperty("feature_count", featureCount(resolved.root()));
		object.addProperty("image_count", imageCount(resolved.root()));
		object.addProperty("image_limit", MAX_IMAGES);
		object.add("images", imageResult.images());
		object.add("warnings", strings(imageResult.warnings()));
		return object;
	}

	public static JsonObject read(ServerPlayer player, SandboxSession sandbox, String document, String featureId) throws IOException {
		if ("index".equals(normalizeDocument(document))) {
			return index(player, sandbox);
		}
		ResolvedDocument resolved = resolveDocument(player, sandbox, document, featureId, false);
		JsonObject object = baseResult(resolved);
		object.addProperty("exists", Files.isRegularFile(resolved.path()));
		if (Files.isRegularFile(resolved.path())) {
			object.addProperty("content", Files.readString(resolved.path(), StandardCharsets.UTF_8));
		} else {
			object.addProperty("content", "");
		}
		return object;
	}

	public static JsonObject deleteImages(ServerPlayer player, SandboxSession sandbox, JsonArray imageNames) throws IOException {
		Path root = root(player, sandbox);
		Path imageDir = root.resolve("images");
		Files.createDirectories(imageDir);
		if (imageNames == null || imageNames.isJsonNull() || imageNames.isEmpty()) {
			throw new IllegalArgumentException("At least one design image name is required.");
		}
		JsonArray deleted = new JsonArray();
		JsonArray missing = new JsonArray();
		for (JsonElement element : imageNames) {
			String fileName = normalizeImageFileName(element.getAsString());
			Path path = imageDir.resolve(fileName).normalize();
			if (!path.startsWith(imageDir.normalize())) {
				throw new IllegalArgumentException("Invalid design image name: " + fileName);
			}
			if (Files.deleteIfExists(path)) {
				deleted.add(fileName);
			} else {
				missing.add(fileName);
			}
		}
		JsonObject object = new JsonObject();
		object.addProperty("root", root.toAbsolutePath().normalize().toString());
		object.addProperty("image_count", imageCount(root));
		object.addProperty("image_limit", MAX_IMAGES);
		object.add("deleted", deleted);
		object.add("missing", missing);
		return object;
	}

	public static JsonObject resource(ServerPlayer player, SandboxSession sandbox, String uri) throws IOException {
		if (uri.equals("design://index")) {
			return index(player, sandbox);
		}
		if (uri.equals("design://agent.md")) {
			return read(player, sandbox, "agent", "");
		}
		if (uri.equals("design://brief.md")) {
			return read(player, sandbox, "brief", "");
		}
		if (uri.equals("design://features")) {
			JsonObject object = new JsonObject();
			object.addProperty("resource", uri);
			object.addProperty("feature_limit", MAX_FEATURES);
			object.add("features", features(root(player, sandbox)));
			return object;
		}
		if (uri.startsWith("design://features/")) {
			String feature = uri.substring("design://features/".length());
			return read(player, sandbox, "feature", feature);
		}
		if (uri.equals("design://images")) {
			JsonObject object = new JsonObject();
			object.addProperty("resource", uri);
			object.addProperty("image_limit", MAX_IMAGES);
			object.addProperty("image_count", imageCount(root(player, sandbox)));
			object.add("images", images(root(player, sandbox)));
			return object;
		}
		if (uri.startsWith("design://images/")) {
			return imageResource(player, sandbox, uri.substring("design://images/".length()));
		}
		throw new IllegalArgumentException("Unknown MineAgent design resource URI: " + uri);
	}

	public static JsonObject index(ServerPlayer player, SandboxSession sandbox) throws IOException {
		Path root = root(player, sandbox);
		Files.createDirectories(root.resolve("features"));
		Files.createDirectories(root.resolve("images"));

		JsonObject object = new JsonObject();
		object.addProperty("player", player.getName().getString());
		object.addProperty("player_uuid", player.getUUID().toString());
		object.addProperty("active_project_id", sandbox.activeProjectId());
		object.addProperty("root", root.toAbsolutePath().normalize().toString());
		object.addProperty("agent_path", root.resolve("agent.md").toAbsolutePath().normalize().toString());
		object.addProperty("brief_path", root.resolve("brief.md").toAbsolutePath().normalize().toString());
		object.addProperty("feature_limit", MAX_FEATURES);
		object.addProperty("image_limit", MAX_IMAGES);
		object.addProperty("image_count", imageCount(root));
		object.addProperty("image_max_dimension", MAX_IMAGE_DIMENSION);
		object.addProperty("image_directory", root.resolve("images").toAbsolutePath().normalize().toString());
		object.add("documents", documents(root));
		object.add("features", features(root));
		object.add("images", images(root));
		return object;
	}

	private static JsonObject baseResult(ResolvedDocument resolved) {
		JsonObject object = new JsonObject();
		object.addProperty("document", resolved.document());
		object.addProperty("id", resolved.id());
		object.addProperty("project_id", resolved.projectId());
		object.addProperty("path", resolved.path().toAbsolutePath().normalize().toString());
		object.addProperty("root", resolved.root().toAbsolutePath().normalize().toString());
		return object;
	}

	private static JsonArray documents(Path root) {
		JsonArray array = new JsonArray();
		array.add(documentInfo("agent", "agent", root.resolve("agent.md")));
		array.add(documentInfo("brief", "brief", root.resolve("brief.md")));
		return array;
	}

	private static JsonArray features(Path root) throws IOException {
		JsonArray array = new JsonArray();
		Path features = root.resolve("features");
		if (!Files.isDirectory(features)) {
			return array;
		}
		try (var stream = Files.list(features)) {
			List<Path> paths = stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().startsWith("feature_"))
					.filter(path -> path.getFileName().toString().endsWith(".md"))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
			for (Path path : paths) {
				String file = path.getFileName().toString();
				array.add(documentInfo("feature", file.substring(0, file.length() - 3), path));
			}
		}
		return array;
	}

	private static JsonArray images(Path root) throws IOException {
		JsonArray array = new JsonArray();
		Path imageDir = root.resolve("images");
		if (!Files.isDirectory(imageDir)) {
			return array;
		}
		try (var stream = Files.list(imageDir)) {
			List<Path> paths = stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".png"))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
			for (Path path : paths) {
				JsonObject item = new JsonObject();
				String fileName = path.getFileName().toString();
				item.addProperty("name", fileName);
				item.addProperty("uri", "design://images/" + fileName);
				item.addProperty("path", path.toAbsolutePath().normalize().toString());
				item.addProperty("bytes", Files.size(path));
				array.add(item);
			}
		}
		return array;
	}

	private static JsonObject imageResource(ServerPlayer player, SandboxSession sandbox, String rawName) throws IOException {
		Path root = root(player, sandbox);
		Path imageDir = root.resolve("images");
		String fileName = normalizeImageFileName(rawName);
		Path path = imageDir.resolve(fileName).normalize();
		if (!path.startsWith(imageDir.normalize())) {
			throw new IllegalArgumentException("Invalid design image name: " + rawName);
		}
		JsonObject object = new JsonObject();
		object.addProperty("resource", "design://images/" + fileName);
		object.addProperty("path", path.toAbsolutePath().normalize().toString());
		object.addProperty("exists", Files.isRegularFile(path));
		object.addProperty("mime_type", "image/png");
		if (Files.isRegularFile(path)) {
			object.addProperty("bytes", Files.size(path));
			object.addProperty("data_base64", Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
		}
		return object;
	}

	private static JsonObject documentInfo(String document, String id, Path path) {
		JsonObject object = new JsonObject();
		object.addProperty("document", document);
		object.addProperty("id", id);
		object.addProperty("path", path.toAbsolutePath().normalize().toString());
		object.addProperty("exists", Files.isRegularFile(path));
		try {
			object.addProperty("chars", Files.isRegularFile(path) ? Files.size(path) : 0L);
		} catch (IOException exception) {
			object.addProperty("size_error", exception.getMessage());
		}
		return object;
	}

	private static ResolvedDocument resolveDocument(ServerPlayer player, SandboxSession sandbox, String rawDocument, String rawFeatureId, boolean createDirectories) throws IOException {
		Path root = root(player, sandbox);
		if (createDirectories) {
			Files.createDirectories(root.resolve("features"));
			Files.createDirectories(root.resolve("images"));
		}

		String document = normalizeDocument(rawDocument);
		return switch (document) {
			case "agent" -> new ResolvedDocument(root, sandbox.activeProjectId(), document, "agent", root.resolve("agent.md"), false);
			case "brief" -> new ResolvedDocument(root, sandbox.activeProjectId(), document, "brief", root.resolve("brief.md"), false);
			case "feature" -> {
				String id = normalizeFeatureId(rawFeatureId);
				yield new ResolvedDocument(root, sandbox.activeProjectId(), document, id, root.resolve("features").resolve(id + ".md"), true);
			}
			default -> throw new IllegalArgumentException("Design document must be one of agent, brief, feature, or index.");
		};
	}

	private static Path root(ServerPlayer player, SandboxSession sandbox) throws IOException {
		return MineAgentProjectStore.activeDesignRoot(player, sandbox);
	}

	private static String normalizeDocument(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.trim().toLowerCase(Locale.ROOT);
	}

	private static String normalizeFeatureId(String raw) {
		String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (value.endsWith(".md")) {
			value = value.substring(0, value.length() - 3);
		}
		if (value.startsWith("features/")) {
			value = value.substring("features/".length());
		}
		if (!value.startsWith("feature_")) {
			value = "feature_" + value;
		}
		value = value.replaceAll("[^a-z0-9_-]+", "_");
		value = value.replaceAll("_+", "_");
		if (!value.matches("feature_[a-z0-9][a-z0-9_-]{0,62}")) {
			throw new IllegalArgumentException("Feature id must become feature_<slug> with letters, digits, underscores, or hyphens.");
		}
		return value;
	}

	private static String safeMarkdown(String content) {
		if (content == null) {
			return "";
		}
		if (content.length() > MAX_MARKDOWN_CHARS) {
			throw new IllegalArgumentException("Design document content exceeds " + MAX_MARKDOWN_CHARS + " characters. Compress the document before writing.");
		}
		return content.replace("\r\n", "\n").replace('\r', '\n');
	}

	private static int featureCount(Path root) throws IOException {
		Path features = root.resolve("features");
		if (!Files.isDirectory(features)) {
			return 0;
		}
		try (var stream = Files.list(features)) {
			return (int) stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().startsWith("feature_"))
					.filter(path -> path.getFileName().toString().endsWith(".md"))
					.count();
		}
	}

	private static int imageCount(Path root) throws IOException {
		Path images = root.resolve("images");
		if (!Files.isDirectory(images)) {
			return 0;
		}
		try (var stream = Files.list(images)) {
			return (int) stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".png"))
					.count();
		}
	}

	private static ImageWriteResult writeImages(Path root, String documentId, JsonArray rawImages) throws IOException {
		JsonArray images = new JsonArray();
		List<String> links = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		if (rawImages == null || rawImages.isJsonNull() || rawImages.isEmpty()) {
			return new ImageWriteResult(images, List.of(), List.of());
		}
		if (rawImages.size() > MAX_IMAGES_PER_WRITE) {
			throw new IllegalArgumentException("A design doc write may include at most " + MAX_IMAGES_PER_WRITE + " image(s).");
		}

		Path imageDir = root.resolve("images");
		Files.createDirectories(imageDir);
		int index = 1;
		for (JsonElement element : rawImages) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("Each image must be an object with name, media_type, and data_base64.");
			}
			JsonObject input = element.getAsJsonObject();
			String name = safeImageName(optional(input, "name"), documentId, index);
			String base64 = required(input, "data_base64");
			byte[] bytes = Base64.getDecoder().decode(stripDataUrlPrefix(base64));
			if (bytes.length > MAX_IMAGE_BYTES) {
				throw new IllegalArgumentException("Image " + name + " exceeds " + MAX_IMAGE_BYTES + " bytes before resizing.");
			}

			BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
			if (source == null) {
				throw new IllegalArgumentException("Image " + name + " could not be decoded.");
			}

			BufferedImage output = source;
			boolean resized = false;
			if (source.getWidth() > MAX_IMAGE_DIMENSION || source.getHeight() > MAX_IMAGE_DIMENSION) {
				double scale = Math.min(
						(double) MAX_IMAGE_DIMENSION / source.getWidth(),
						(double) MAX_IMAGE_DIMENSION / source.getHeight());
				int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
				int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
				output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = output.createGraphics();
				graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				graphics.drawImage(source, 0, 0, width, height, null);
				graphics.dispose();
				resized = true;
				warnings.add("Image " + name + " was resized from " + source.getWidth() + "x" + source.getHeight() + " to " + width + "x" + height + ".");
			}

			Path path = uniqueImagePath(imageDir, name);
			ByteArrayOutputStream encoded = new ByteArrayOutputStream();
			ImageIO.write(output, "png", encoded);
			Files.write(path, encoded.toByteArray());

			String relative = "images/" + path.getFileName();
			links.add("![" + name + "](" + relative + ")");
			JsonObject image = new JsonObject();
			image.addProperty("name", name);
			image.addProperty("path", path.toAbsolutePath().normalize().toString());
			image.addProperty("markdown", "![" + name + "](" + relative + ")");
			image.addProperty("width", output.getWidth());
			image.addProperty("height", output.getHeight());
			image.addProperty("resized", resized);
			images.add(image);
			index++;
		}
		return new ImageWriteResult(images, links, warnings);
	}

	private static Path uniqueImagePath(Path imageDir, String name) {
		String base = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
		Path path = imageDir.resolve(base + ".png");
		int suffix = 2;
		while (Files.exists(path)) {
			path = imageDir.resolve(base + "-" + suffix + ".png");
			suffix++;
		}
		return path;
	}

	private static String safeImageName(String raw, String documentId, int index) {
		String value = raw == null || raw.isBlank() ? documentId + "_image_" + index : raw.trim().toLowerCase(Locale.ROOT);
		if (value.endsWith(".png") || value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".webp")) {
			int dot = value.lastIndexOf('.');
			value = value.substring(0, dot);
		}
		value = value.replaceAll("[^a-z0-9_-]+", "_").replaceAll("_+", "_");
		if (value.isBlank()) {
			value = documentId + "_image_" + index;
		}
		if (value.length() > 80) {
			value = value.substring(0, 80);
		}
		return value;
	}

	private static String normalizeImageFileName(String raw) {
		String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (value.startsWith("images/")) {
			value = value.substring("images/".length());
		}
		value = value.replace('\\', '/');
		if (value.contains("/") || value.contains("..")) {
			throw new IllegalArgumentException("Image name must be a single file name inside the design images directory.");
		}
		if (!value.endsWith(".png")) {
			value = value + ".png";
		}
		if (!value.matches("[a-z0-9_-][a-z0-9_.-]{0,95}\\.png")) {
			throw new IllegalArgumentException("Image name must use letters, digits, underscore, hyphen, dot, and .png.");
		}
		return value;
	}

	private static String stripDataUrlPrefix(String raw) {
		String value = raw.trim();
		int comma = value.indexOf(',');
		if (value.startsWith("data:") && comma >= 0) {
			return value.substring(comma + 1);
		}
		return value;
	}

	private static String optional(JsonObject object, String name) {
		if (!object.has(name) || object.get(name).isJsonNull()) {
			return "";
		}
		return object.get(name).getAsString();
	}

	private static String required(JsonObject object, String name) {
		String value = optional(object, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException("Missing required image field: " + name);
		}
		return value;
	}

	private static JsonArray strings(List<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private record ResolvedDocument(Path root, String projectId, String document, String id, Path path, boolean feature) {
	}

	private record ImageWriteResult(JsonArray images, List<String> markdownLinks, List<String> warnings) {
	}
}
