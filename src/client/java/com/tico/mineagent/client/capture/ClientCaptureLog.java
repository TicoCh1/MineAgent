package com.tico.mineagent.client.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;

import com.tico.mineagent.MineAgent;

public final class ClientCaptureLog {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
	private static final AtomicInteger NEXT_ID = new AtomicInteger();

	private ClientCaptureLog() {
	}

	public static synchronized String save(String captureType, String label, NativeImage image) {
		try {
			Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-logs").resolve("captures");
			Files.createDirectories(directory);
			String timestamp = FILE_TIME.format(Instant.now());
			String safeType = safeFilePart(captureType);
			String safeLabel = safeFilePart(label);
			String fileName = timestamp + "-" + safeType + "-" + safeLabel + "-" + NEXT_ID.incrementAndGet() + ".png";
			Path path = directory.resolve(fileName);
			image.writeToFile(path);
			appendIndex(directory, captureType, label, image, path);
			return path.toAbsolutePath().toString();
		} catch (IOException exception) {
			MineAgent.LOGGER.warn("Failed to save MineAgent capture image", exception);
			return "";
		}
	}

	private static void appendIndex(Path directory, String captureType, String label, NativeImage image, Path imagePath) throws IOException {
		JsonObject line = new JsonObject();
		line.addProperty("time", Instant.now().toString());
		line.addProperty("capture_type", captureType);
		line.addProperty("label", label);
		line.addProperty("width", image.getWidth());
		line.addProperty("height", image.getHeight());
		line.addProperty("path", imagePath.toAbsolutePath().toString());
		Files.writeString(
				directory.resolve("captures.jsonl"),
				GSON.toJson(line) + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
	}

	private static String safeFilePart(String value) {
		String safe = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return safe.isBlank() ? "capture" : safe;
	}
}
