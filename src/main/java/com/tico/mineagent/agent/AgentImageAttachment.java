package com.tico.mineagent.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public record AgentImageAttachment(String label, String mediaType, Path path) {
	public AgentImageAttachment {
		label = label == null || label.isBlank() ? "image" : label;
		mediaType = mediaType == null || mediaType.isBlank() ? "image/png" : mediaType;
		path = path.toAbsolutePath().normalize();
	}

	public String base64Data() throws IOException {
		return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
	}

	public String dataUrl() throws IOException {
		return "data:" + mediaType + ";base64," + base64Data();
	}
}
