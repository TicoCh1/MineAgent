package com.tico.mineagent.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import com.tico.mineagent.client.raycast.RaycastImageSet;
import com.tico.mineagent.client.capture.GpuCaptureImageSet;
import com.tico.mineagent.client.model.ClientModelCatalog;
import com.tico.mineagent.client.state.ClientAgentUiState;
import com.tico.mineagent.client.state.ClientSandboxState;
import com.tico.mineagent.agent.AgentPlanItem;
import com.tico.mineagent.agent.AgentPlanSnapshot;

public class MineAgentControlScreen extends Screen {
	private static final int BACKDROP = 0x8A05070B;
	private static final int PANEL = 0xD9161B22;
	private static final int PANEL_ALT = 0xE01D2530;
	private static final int PANEL_EDGE = 0xFF465568;
	private static final int PANEL_EDGE_STRONG = 0xFF8CB7D8;
	private static final int TEXT = 0xFFE9EEF5;
	private static final int MUTED = 0xFFA8B3C0;
	private static final int SUBTLE = 0xFF6E7A89;
	private static final int ACCENT = 0xFF7DD3FC;
	private static final int OK = 0xFF92E6A7;
	private static final int WARN = 0xFFFFD166;
	private static final int ERROR = 0xFFFF7A90;
	private static final int UI = 0xFFB9A7FF;
	private static final int FIELD_HEIGHT = 20;
	private static final int GAP = 8;

	private String providerId = ClientAgentUiState.providerId();
	private String selectedModel = ClientAgentUiState.model();
	private List<String> modelOptions = new ArrayList<>();
	private String modelListStatus = "offline defaults";
	private boolean modelDropdownOpen;
	private int modelScroll;
	private Button modelButton;
	private Button refreshModelsButton;
	private EditBox apiKeyBox;
	private EditBox projectBox;
	private EditBox promptBox;
	private EditBox raycastFovBox;
	private Button openAiButton;
	private Button claudeButton;
	private Button continueButton;
	private Button sandboxModeButton;
	private Button expandSandboxButton;
	private Button rejectSandboxButton;
	private Button raycastModeButton;
	private Button[] resolutionButtons = new Button[0];
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int logX;
	private int logY;
	private int logW;
	private int logH;
	private int scrollLines;

	public MineAgentControlScreen() {
		super(Component.literal("MineAgent Control"));
	}

	@Override
	protected void init() {
		String apiKeyDraft = apiKeyBox == null ? "" : apiKeyBox.getValue();
		String projectDraft = projectBox == null ? ClientAgentUiState.activeProjectId() : projectBox.getValue();
		String promptDraft = promptBox == null ? "" : promptBox.getValue();
		String fovDraft = raycastFovBox == null ? String.format(Locale.ROOT, "%.1f", ClientAgentUiState.raycastFov()) : raycastFovBox.getValue();
		if (!ClientAgentUiState.providerId().isBlank()) {
			providerId = ClientAgentUiState.providerId();
		}
		ensureModelOptions();

		panelW = Math.min(width - 24, 980);
		panelH = Math.min(height - 24, 580);
		panelW = Math.max(panelW, Math.min(width - 12, 420));
		panelH = Math.max(panelH, Math.min(height - 12, 300));
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;

		int topH = 34;
		int bottomH = 42;
		int rightW = Mth.clamp(panelW / 3, 210, 286);
		int contentY = panelY + topH + GAP;
		int contentH = panelH - topH - bottomH - GAP * 3;
		logX = panelX + GAP;
		logY = contentY;
		logW = panelW - rightW - GAP * 3;
		logH = contentH;
		int rightX = logX + logW + GAP;
		int rightY = contentY;
		int fieldW = rightW - GAP * 2;

		openAiButton = addRenderableWidget(Button.builder(Component.literal("OpenAI"), button -> {
			providerId = "openai";
			resetModelsForProvider();
			updateProviderButtons();
		}).bounds(rightX + GAP, rightY + 23, (fieldW - GAP) / 2, 20).build());
		claudeButton = addRenderableWidget(Button.builder(Component.literal("Claude"), button -> {
			providerId = "claude";
			resetModelsForProvider();
			updateProviderButtons();
		}).bounds(rightX + GAP + (fieldW + GAP) / 2, rightY + 23, (fieldW - GAP) / 2, 20).build());

		modelButton = addRenderableWidget(Button.builder(Component.literal("Model"), button -> {
			modelDropdownOpen = !modelDropdownOpen;
			modelScroll = 0;
			updateModelButton();
		}).bounds(rightX + GAP, rightY + 63, fieldW - 46, FIELD_HEIGHT).build());
		refreshModelsButton = addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> refreshModelList())
				.bounds(rightX + GAP + fieldW - 42, rightY + 63, 42, FIELD_HEIGHT)
				.build());

		apiKeyBox = addRenderableWidget(new EditBox(font, rightX + GAP, rightY + 103, fieldW, FIELD_HEIGHT, Component.literal("API key")));
		apiKeyBox.setMaxLength(8192);
		apiKeyBox.setValue(apiKeyDraft);
		apiKeyBox.setHint(Component.literal("API key, memory only"));
		apiKeyBox.addFormatter((value, cursor) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));

		addRenderableWidget(Button.builder(Component.literal("Save Session"), button -> saveSession())
				.bounds(rightX + GAP, rightY + 131, fieldW, 20)
				.build());
		addRenderableWidget(Button.builder(Component.literal("Stop Agent"), button -> {
			ClientAgentUiState.sendStop();
			ClientAgentUiState.addLocal("Stop request sent.");
		}).bounds(rightX + GAP, rightY + 157, fieldW, 20).build());
		continueButton = addRenderableWidget(Button.builder(Component.literal("Continue +64"), button -> {
			ClientAgentUiState.sendContinue();
			ClientAgentUiState.addLocal("Continuation approval sent.");
		}).bounds(rightX + GAP, rightY + 183, fieldW, 20).build());

		sandboxModeButton = addRenderableWidget(Button.builder(Component.literal("Sandbox"), button -> {
			String nextMode = nextSandboxMode(ClientAgentUiState.sandboxPermissionMode());
			ClientAgentUiState.sendSandboxPermissionMode(nextMode);
			ClientAgentUiState.addLocal("Sandbox permission mode change requested: " + sandboxModeLabel(nextMode) + ".");
		}).bounds(rightX + GAP, rightY + 209, fieldW, 20).build());
		expandSandboxButton = addRenderableWidget(Button.builder(Component.literal("Expand Sandbox"), button -> {
			ClientAgentUiState.sendSandboxExpansionReply(true);
			ClientAgentUiState.addLocal("Sandbox expansion approval sent.");
		}).bounds(rightX + GAP, rightY + 235, (fieldW - GAP) / 2, 20).build());
		rejectSandboxButton = addRenderableWidget(Button.builder(Component.literal("Reject"), button -> {
			ClientAgentUiState.sendSandboxExpansionReply(false);
			ClientAgentUiState.addLocal("Sandbox expansion rejection sent.");
		}).bounds(rightX + GAP + (fieldW + GAP) / 2, rightY + 235, (fieldW - GAP) / 2, 20).build());

		raycastFovBox = addRenderableWidget(new EditBox(font, rightX + GAP, rightY + 269, fieldW, FIELD_HEIGHT, Component.literal("Raycast FOV")));
		raycastFovBox.setMaxLength(16);
		raycastFovBox.setValue(fovDraft);
		raycastFovBox.setHint(Component.literal("FOV degrees"));

		int[] sizes = new int[] { 128, 224, 256, 512, 768 };
		resolutionButtons = new Button[sizes.length];
		int buttonW = Math.max(32, (fieldW - GAP * (sizes.length - 1)) / sizes.length);
		for (int i = 0; i < sizes.length; i++) {
			int size = sizes[i];
			int index = i;
			resolutionButtons[i] = addRenderableWidget(Button.builder(Component.literal(Integer.toString(size)), button -> {
				ClientAgentUiState.setRaycastSize(size);
				updateResolutionButtons();
			}).bounds(rightX + GAP + i * (buttonW + GAP), rightY + 297, buttonW, 20).build());
			resolutionButtons[i].setMessage(Component.literal(Integer.toString(sizes[index])));
		}
		raycastModeButton = addRenderableWidget(Button.builder(Component.literal("Mode: sandbox"), button -> {
			ClientAgentUiState.toggleRaycastMode();
			updateRaycastModeButton();
		}).bounds(rightX + GAP, rightY + 323, (fieldW - GAP) / 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Raycast"), button -> captureRaycast())
				.bounds(rightX + GAP + (fieldW + GAP) / 2, rightY + 323, (fieldW - GAP) / 2, 20)
				.build());

		int promptY = panelY + panelH - bottomH + 12;
		projectBox = addRenderableWidget(new EditBox(font, panelX + GAP + 52, promptY, 104, FIELD_HEIGHT, Component.literal("Project")));
		projectBox.setMaxLength(64);
		projectBox.setValue(projectDraft == null || projectDraft.isBlank() ? "default" : projectDraft);
		projectBox.setHint(Component.literal("project id"));

		promptBox = addRenderableWidget(new EditBox(font, panelX + GAP + 206, promptY, panelW - 394, FIELD_HEIGHT, Component.literal("Agent prompt")));
		promptBox.setMaxLength(4096);
		promptBox.setValue(promptDraft);
		promptBox.setHint(Component.literal("Describe the build task for the agent"));
		addRenderableWidget(Button.builder(Component.literal("Start"), button -> startAgent(false))
				.bounds(panelX + panelW - 176, promptY, 48, 20)
				.build());
		addRenderableWidget(Button.builder(Component.literal("Continue"), button -> startAgent(true))
				.bounds(panelX + panelW - 124, promptY, 64, 20)
				.build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
				.bounds(panelX + panelW - 56, promptY, 48, 20)
				.build());

		updateProviderButtons();
		updateResolutionButtons();
		updateRaycastModeButton();
		updateSandboxModeButton();
		setInitialFocus(promptBox);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		updateDynamicButtons();
		graphics.fill(0, 0, width, height, BACKDROP);
		drawPanel(graphics, panelX, panelY, panelW, panelH, PANEL, PANEL_EDGE_STRONG);
		renderHeader(graphics);
		renderLogPanel(graphics, mouseX, mouseY);
		renderControlPanel(graphics);
		renderPromptBar(graphics);
		super.render(graphics, mouseX, mouseY, partialTick);
		renderModelDropdown(graphics, mouseX, mouseY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (promptBox != null && promptBox.isFocused() && event.isConfirmation()) {
			startAgent(false);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (modelDropdownOpen && inside(mouseX, mouseY, modelDropdownX(), modelDropdownY(), modelDropdownW(), modelDropdownH())) {
			int visible = modelDropdownVisibleRows();
			modelScroll = Mth.clamp(modelScroll + (scrollY > 0.0D ? -1 : 1), 0, Math.max(0, modelOptions.size() - visible));
			return true;
		}
		if (inside(mouseX, mouseY, logX, logY, logW, logH)) {
			scrollLines = Mth.clamp(scrollLines + (scrollY > 0.0D ? 3 : -3), 0, 2000);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		if (event.button() == 0) {
			if (modelDropdownOpen) {
				int modelIndex = modelIndexAt(mouseX, mouseY);
				if (modelIndex >= 0 && modelIndex < modelOptions.size()) {
					selectedModel = modelOptions.get(modelIndex);
					modelDropdownOpen = false;
					updateModelButton();
					ClientAgentUiState.addLocal("Selected model: " + selectedModel);
					return true;
				}
				if (!inside(mouseX, mouseY, modelDropdownX(), modelDropdownY(), modelDropdownW(), modelDropdownH())
						&& (modelButton == null || !inside(mouseX, mouseY, modelButton.getX(), modelButton.getY(), modelButton.getWidth(), modelButton.getHeight()))) {
					modelDropdownOpen = false;
					updateModelButton();
				}
			}
			LogTextArea area = logTextArea();
			if (inside(mouseX, mouseY, area.x(), area.y(), area.w(), area.h())) {
				List<Line> lines = wrappedLogLines(area.w());
				int visible = Math.max(1, area.h() / (font.lineHeight + 2));
				scrollLines = Mth.clamp(scrollLines, 0, Math.max(0, lines.size() - visible));
				int start = Math.max(0, lines.size() - visible - scrollLines);
				int row = (int) ((mouseY - area.y() - 2) / (font.lineHeight + 2));
				int lineIndex = start + row;
				if (row >= 0 && lineIndex >= 0 && lineIndex < lines.size()) {
					Line line = lines.get(lineIndex);
					if (line.toggle()) {
						ClientAgentUiState.toggleLogEntry(line.entryIndex());
						return true;
					}
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	private void renderHeader(GuiGraphics graphics) {
		int top = panelY + 8;
		graphics.drawString(font, "MineAgent", panelX + 14, top, TEXT, false);
		graphics.drawString(font, "Agentic build control", panelX + 84, top, MUTED, false);
		int badgeX = panelX + panelW - 270;
		drawBadge(graphics, badgeX, top - 2, 76, ClientAgentUiState.configured() ? "configured" : "no config", ClientAgentUiState.configured() ? OK : WARN);
		drawBadge(graphics, badgeX + 82, top - 2, 82, providerId, ACCENT);
		drawBadge(graphics, badgeX + 170, top - 2, 100, ClientAgentUiState.status(), statusColor(), true);
		graphics.fill(panelX + GAP, panelY + 30, panelX + panelW - GAP, panelY + 31, 0x66394755);
	}

	private void renderLogPanel(GuiGraphics graphics, int mouseX, int mouseY) {
		drawPanel(graphics, logX, logY, logW, logH, PANEL_ALT, PANEL_EDGE);
		graphics.drawString(font, "Conversation and execution trace", logX + 10, logY + 8, TEXT, false);
		graphics.drawString(font, "Scroll to inspect older entries", logX + logW - 142, logY + 8, SUBTLE, false);
		int bodyX = logX + 10;
		int bodyY = logY + 26;
		int bodyW = logW - 20;
		int bodyH = logH - 36;
		GpuCaptureImageSet gpuCapture = ClientAgentUiState.gpuCaptureResult();
		RaycastImageSet raycast = ClientAgentUiState.raycastResult();
		if ((gpuCapture != null || raycast != null) && bodyH > 220) {
			int imageAreaH = Math.min(260, bodyH / 2 + 64);
			if (gpuCapture != null) {
				renderGpuCaptureImages(graphics, gpuCapture, bodyX, bodyY, bodyW, imageAreaH);
			} else {
				renderRaycastImages(graphics, raycast, bodyX, bodyY, bodyW, imageAreaH);
			}
			bodyY += imageAreaH + GAP;
			bodyH -= imageAreaH + GAP;
		}
		List<Line> lines = wrappedLogLines(bodyW);
		int visible = Math.max(1, bodyH / (font.lineHeight + 2));
		scrollLines = Mth.clamp(scrollLines, 0, Math.max(0, lines.size() - visible));
		int start = Math.max(0, lines.size() - visible - scrollLines);
		int end = Math.min(lines.size(), start + visible);

		graphics.enableScissor(bodyX, bodyY, bodyX + bodyW, bodyY + bodyH);
		if (lines.isEmpty()) {
			graphics.drawString(font, "No agent events yet. Configure a session and start a task.", bodyX, bodyY + 4, MUTED, false);
		} else {
			int y = bodyY + 2;
			for (int i = start; i < end; i++) {
				Line line = lines.get(i);
				graphics.drawString(font, line.text(), bodyX, y, line.color(), false);
				y += font.lineHeight + 2;
			}
		}
		graphics.disableScissor();
		if (inside(mouseX, mouseY, logX, logY, logW, logH)) {
			graphics.setTooltipForNextFrame(font, Component.literal("Mouse wheel scrolls the log. Click + rows to expand calls."), mouseX, mouseY);
		}
	}

	private void renderControlPanel(GuiGraphics graphics) {
		int rightX = logX + logW + GAP;
		int rightY = logY;
		int rightW = panelX + panelW - GAP - rightX;
		drawPanel(graphics, rightX, rightY, rightW, logH, PANEL_ALT, PANEL_EDGE);
		graphics.drawString(font, "Session Control", rightX + 10, rightY + 8, TEXT, false);
		graphics.drawString(font, "Model", rightX + 10, rightY + 52, MUTED, false);
		String status = font.plainSubstrByWidth(modelListStatus, Math.max(20, rightW - 64));
		graphics.drawString(font, status, rightX + rightW - font.width(status) - 10, rightY + 52, SUBTLE, false);
		graphics.drawString(font, "API Key", rightX + 10, rightY + 92, MUTED, false);
		if (ClientAgentUiState.awaitingApproval()) {
			graphics.drawString(font, "waiting after " + ClientAgentUiState.completedSteps() + " steps", rightX + 10, rightY + 209, WARN, false);
		}
		graphics.drawString(font, "Raycast", rightX + 10, rightY + 215, MUTED, false);
		graphics.drawString(font, ClientAgentUiState.raycastSize() + "x" + ClientAgentUiState.raycastSize(), rightX + rightW - 62, rightY + 215, SUBTLE, false);
		int nextY = renderPlanSection(graphics, rightX + 10, rightY + 314, rightW - 20, rightY + logH - 76);
		int infoY = nextY + 8;
		int bottom = rightY + logH - 10;
		if (infoY + 34 < bottom) {
			graphics.drawString(font, "Now", rightX + 10, infoY, MUTED, false);
			graphics.drawWordWrap(font, Component.literal(ClientAgentUiState.currentActivity()), rightX + 10, infoY + 14, rightW - 20, TEXT);
		}
		int sandboxY = infoY + 48;
		if (sandboxY + 62 > bottom) {
			sandboxY = Math.max(infoY + 48, bottom - 62);
		}
		if (sandboxY + 62 <= bottom) {
			graphics.drawString(font, "Sandbox", rightX + 10, sandboxY, MUTED, false);
			graphics.drawWordWrap(font, Component.literal(ClientSandboxState.summary()), rightX + 10, sandboxY + 14, rightW - 20, ClientSandboxState.complete() ? OK : WARN);
			graphics.drawString(font, "Selector: " + ClientSandboxState.selectorId(), rightX + 10, sandboxY + 42, SUBTLE, false);
			graphics.drawString(font, "Player: " + ClientAgentUiState.playerName(), rightX + 10, sandboxY + 56, SUBTLE, false);
		}
	}

	private int renderPlanSection(GuiGraphics graphics, int x, int y, int w, int maxY) {
		graphics.drawString(font, "Progress", x, y, MUTED, false);
		AgentPlanSnapshot plan = ClientAgentUiState.plan();
		int rowY = y + 14;
		if (!plan.explanation().isBlank() && rowY + font.lineHeight < maxY) {
			String text = font.plainSubstrByWidth(plan.explanation(), w);
			graphics.drawString(font, text, x, rowY, SUBTLE, false);
			rowY += font.lineHeight + 3;
		}
		if (plan.items().isEmpty()) {
			if (rowY + font.lineHeight < maxY) {
				graphics.drawString(font, "No visible plan yet.", x, rowY, SUBTLE, false);
				rowY += font.lineHeight + 3;
			}
			return rowY;
		}
		for (int index = 0; index < plan.items().size(); index++) {
			AgentPlanItem item = plan.items().get(index);
			if (rowY + font.lineHeight >= maxY) {
				String more = "+" + (plan.items().size() - index) + " more";
				graphics.drawString(font, more, x, rowY, SUBTLE, false);
				rowY += font.lineHeight + 3;
				break;
			}
			String marker = switch (item.status()) {
				case AgentPlanItem.COMPLETED -> "[x] ";
				case AgentPlanItem.IN_PROGRESS -> "[>] ";
				default -> "[ ] ";
			};
			int color = switch (item.status()) {
				case AgentPlanItem.COMPLETED -> OK;
				case AgentPlanItem.IN_PROGRESS -> ACCENT;
				default -> TEXT;
			};
			String line = font.plainSubstrByWidth(marker + item.step(), w);
			graphics.drawString(font, line, x, rowY, color, false);
			rowY += font.lineHeight + 3;
		}
		return rowY;
	}

	private void renderPromptBar(GuiGraphics graphics) {
		int y = panelY + panelH - 42;
		graphics.fill(panelX + GAP, y, panelX + panelW - GAP, y + 1, 0x66394755);
		graphics.drawString(font, "Project", panelX + GAP + 4, y + 15, MUTED, false);
		graphics.drawString(font, "Prompt", panelX + GAP + 162, y + 15, MUTED, false);
	}

	private void renderRaycastImages(GuiGraphics graphics, RaycastImageSet result, int x, int y, int w, int h) {
		graphics.fill(x, y, x + w, y + h, 0x880B1018);
		graphics.fill(x, y, x + w, y + 1, 0x66465568);
		graphics.drawString(font, "Raycast capture", x + 8, y + 7, TEXT, false);
		String meta = result.mode() + "  " + result.size() + "x" + result.size() + "  " + String.format(Locale.ROOT, "%.1f", result.fovDegrees()) + " deg  " + result.durationMillis() + " ms";
		graphics.drawString(font, meta, x + w - Math.min(font.width(meta), w - 120) - 8, y + 7, MUTED, false);

		int top = y + 24;
		int cellGap = 8;
		int cellW = (w - cellGap) / 2;
		int cellH = (h - 30 - cellGap) / 2;
		drawImageCell(graphics, "color", result.colorTexture(), x, top, cellW, cellH, result.size());
		drawImageCell(graphics, "depth", result.depthTexture(), x + cellW + cellGap, top, cellW, cellH, result.size());
		drawImageCell(graphics, "block id", result.blockIdTexture(), x, top + cellH + cellGap, cellW, cellH, result.size());
		drawImageCell(graphics, "xyz", result.positionTexture(), x + cellW + cellGap, top + cellH + cellGap, cellW, cellH, result.size());
	}

	private void renderGpuCaptureImages(GuiGraphics graphics, GpuCaptureImageSet result, int x, int y, int w, int h) {
		graphics.fill(x, y, x + w, y + h, 0x880B1018);
		graphics.fill(x, y, x + w, y + 1, 0x66465568);
		graphics.drawString(font, "GPU capture", x + 8, y + 7, TEXT, false);
		String meta = result.type() + "  " + result.width() + "x" + result.height() + "  " + String.format(Locale.ROOT, "%.1f", result.fovDegrees()) + " deg  " + result.durationMillis() + " ms";
		graphics.drawString(font, meta, x + w - Math.min(font.width(meta), w - 110) - 8, y + 7, MUTED, false);

		int count = Math.max(1, result.images().size());
		int cols = count == 1 ? 1 : count <= 4 ? 2 : 4;
		int rows = Mth.ceil(count / (double) cols);
		int cellGap = 8;
		int top = y + 24;
		int availableH = Math.max(1, h - 30);
		int cellW = Math.max(1, (w - cellGap * (cols - 1)) / cols);
		int cellH = Math.max(1, (availableH - cellGap * (rows - 1)) / rows);
		for (int i = 0; i < result.images().size(); i++) {
			GpuCaptureImageSet.Image image = result.images().get(i);
			int col = i % cols;
			int row = i / cols;
			drawImageCell(graphics, image.label(), image.texture(), x + col * (cellW + cellGap), top + row * (cellH + cellGap), cellW, cellH, image.width(), image.height());
		}
	}

	private void drawImageCell(GuiGraphics graphics, String label, ResourceLocation texture, int x, int y, int w, int h, int textureSize) {
		drawImageCell(graphics, label, texture, x, y, w, h, textureSize, textureSize);
	}

	private void drawImageCell(GuiGraphics graphics, String label, ResourceLocation texture, int x, int y, int w, int h, int textureWidth, int textureHeight) {
		graphics.fill(x, y, x + w, y + h, 0xAA05070B);
		graphics.drawString(font, label, x + 4, y + 3, MUTED, false);
		int availableW = Math.max(1, w - 8);
		int availableH = Math.max(1, h - 18);
		int[] imageSize = scaledPreviewSize(textureWidth, textureHeight, availableW, availableH);
		int imageX = x + (w - imageSize[0]) / 2;
		int imageY = y + 14 + (availableH - imageSize[1]) / 2;
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, imageX, imageY, 0.0F, 0.0F, imageSize[0], imageSize[1], textureWidth, textureHeight, textureWidth, textureHeight);
	}

	private int scaledPreviewSize(int textureSize, int availableW, int availableH) {
		return scaledPreviewSize(textureSize, textureSize, availableW, availableH)[0];
	}

	private int[] scaledPreviewSize(int textureWidth, int textureHeight, int availableW, int availableH) {
		int sourceW = Math.max(1, textureWidth);
		int sourceH = Math.max(1, textureHeight);
		int maxW = Math.max(1, availableW);
		int maxH = Math.max(1, availableH);
		double scale = Math.min(1.0D, Math.min(maxW / (double) sourceW, maxH / (double) sourceH));
		return new int[] { Math.max(1, Mth.floor(sourceW * scale)), Math.max(1, Mth.floor(sourceH * scale)) };
	}

	private void saveSession() {
		String model = selectedModel.trim();
		String apiKey = apiKeyBox.getValue().trim();
		ClientAgentUiState.sendConfigure(normalizedProvider(), model, apiKey);
		if (!apiKey.isBlank()) {
			apiKeyBox.setValue("");
		}
		ClientAgentUiState.addLocal("Configuration submitted.");
	}

	private void startAgent(boolean continueProject) {
		String prompt = promptBox.getValue().trim();
		String apiKey = apiKeyBox.getValue().trim();
		String projectId = projectBox == null ? "" : projectBox.getValue().trim();
		ClientAgentUiState.sendStart(normalizedProvider(), selectedModel.trim(), apiKey, projectId, continueProject, prompt);
		if (!apiKey.isBlank()) {
			apiKeyBox.setValue("");
		}
		if (!prompt.isBlank()) {
			ClientAgentUiState.addLocal((continueProject ? "Continue" : "Start") + " request sent for project " + (projectId.isBlank() ? ClientAgentUiState.activeProjectId() : projectId) + ": " + prompt);
			promptBox.setValue("");
			scrollLines = 0;
		}
	}

	private void captureRaycast() {
		double fov = parseRaycastFov();
		ClientAgentUiState.setRaycastFov(fov);
		raycastFovBox.setValue(String.format(Locale.ROOT, "%.1f", ClientAgentUiState.raycastFov()));
		ClientAgentUiState.runRaycast(ClientAgentUiState.raycastSize(), ClientAgentUiState.raycastFov(), ClientAgentUiState.raycastMode());
		scrollLines = 0;
	}

	private double parseRaycastFov() {
		try {
			return Mth.clamp(Double.parseDouble(raycastFovBox.getValue().trim()), 1.0D, 170.0D);
		} catch (NumberFormatException exception) {
			ClientAgentUiState.addLocal("Invalid raycast FOV; using previous value.");
			return ClientAgentUiState.raycastFov();
		}
	}

	private void ensureModelOptions() {
		if (modelOptions.isEmpty()) {
			modelOptions = new ArrayList<>(ClientModelCatalog.defaults(providerId));
			modelListStatus = "offline defaults";
		}
		String configuredModel = ClientAgentUiState.model();
		if ((selectedModel == null || selectedModel.isBlank()) && configuredModel != null && !configuredModel.isBlank()) {
			selectedModel = configuredModel;
		}
		if (selectedModel == null || selectedModel.isBlank()) {
			selectedModel = modelOptions.isEmpty() ? "" : modelOptions.get(0);
		}
		if (!selectedModel.isBlank() && !modelOptions.contains(selectedModel)) {
			modelOptions.add(0, selectedModel);
		}
	}

	private void resetModelsForProvider() {
		modelDropdownOpen = false;
		modelScroll = 0;
		modelOptions = new ArrayList<>(ClientModelCatalog.defaults(providerId));
		selectedModel = modelOptions.isEmpty() ? "" : modelOptions.get(0);
		modelListStatus = "offline defaults";
		updateModelButton();
	}

	private void refreshModelList() {
		String provider = normalizedProvider();
		String apiKey = apiKeyBox == null ? "" : apiKeyBox.getValue().trim();
		modelListStatus = "refreshing...";
		if (refreshModelsButton != null) {
			refreshModelsButton.active = false;
			refreshModelsButton.setMessage(Component.literal("..."));
		}
		updateModelButton();
		ClientAgentUiState.addLocal("Refreshing " + provider + " model list.");
		ClientModelCatalog.refresh(provider, apiKey).whenComplete((result, throwable) -> {
			Runnable apply = () -> {
				if (!provider.equals(normalizedProvider())) {
					return;
				}
				if (throwable != null) {
					modelListStatus = "refresh failed";
					ClientAgentUiState.addLocal("Model list refresh failed: " + throwable.getMessage());
				} else {
					modelOptions = new ArrayList<>(result.models());
					if (modelOptions.isEmpty()) {
						modelOptions = new ArrayList<>(ClientModelCatalog.defaults(provider));
					}
					if (selectedModel == null || selectedModel.isBlank() || !modelOptions.contains(selectedModel)) {
						selectedModel = modelOptions.get(0);
					}
					modelListStatus = result.source();
					ClientAgentUiState.addLocal(result.message());
				}
				modelScroll = 0;
				updateModelButton();
				if (refreshModelsButton != null) {
					refreshModelsButton.active = true;
					refreshModelsButton.setMessage(Component.literal("Refresh"));
				}
			};
			if (minecraft != null) {
				minecraft.execute(apply);
			} else {
				apply.run();
			}
		});
	}

	private void updateModelButton() {
		if (modelButton == null) {
			return;
		}
		String marker = modelDropdownOpen ? "v " : "> ";
		String label = marker + (selectedModel == null || selectedModel.isBlank() ? "Select model" : selectedModel);
		modelButton.setMessage(Component.literal(font.plainSubstrByWidth(label, Math.max(20, modelButton.getWidth() - 10))));
	}

	private void renderModelDropdown(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!modelDropdownOpen || modelButton == null || modelOptions.isEmpty()) {
			return;
		}
		int x = modelDropdownX();
		int y = modelDropdownY();
		int w = modelDropdownW();
		int h = modelDropdownH();
		graphics.fill(x, y, x + w, y + h, 0xF20B1018);
		graphics.fill(x, y, x + w, y + 1, PANEL_EDGE_STRONG);
		graphics.fill(x, y + h - 1, x + w, y + h, PANEL_EDGE);
		int visible = modelDropdownVisibleRows();
		modelScroll = Mth.clamp(modelScroll, 0, Math.max(0, modelOptions.size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = modelScroll + row;
			if (index >= modelOptions.size()) {
				break;
			}
			int rowY = y + 1 + row * 18;
			String model = modelOptions.get(index);
			boolean selected = model.equals(selectedModel);
			boolean hover = inside(mouseX, mouseY, x, rowY, w, 18);
			if (selected || hover) {
				graphics.fill(x + 1, rowY, x + w - 1, rowY + 18, selected ? 0xAA1D4B66 : 0x88465568);
			}
			String text = font.plainSubstrByWidth(model, w - 12);
			graphics.drawString(font, text, x + 6, rowY + 5, selected ? OK : TEXT, false);
		}
		if (modelOptions.size() > visible) {
			String page = (modelScroll + 1) + "-" + Math.min(modelOptions.size(), modelScroll + visible) + "/" + modelOptions.size();
			graphics.drawString(font, page, x + w - font.width(page) - 6, y + h - 12, SUBTLE, false);
		}
		if (inside(mouseX, mouseY, x, y, w, h)) {
			graphics.setTooltipForNextFrame(font, Component.literal("Select a provider model. Refresh uses the current API key and official model-list endpoint."), mouseX, mouseY);
		}
	}

	private int modelDropdownX() {
		return modelButton == null ? 0 : modelButton.getX();
	}

	private int modelDropdownY() {
		return modelButton == null ? 0 : modelButton.getY() + modelButton.getHeight() + 2;
	}

	private int modelDropdownW() {
		if (modelButton == null) {
			return 0;
		}
		if (refreshModelsButton == null) {
			return modelButton.getWidth();
		}
		return refreshModelsButton.getX() + refreshModelsButton.getWidth() - modelButton.getX();
	}

	private int modelDropdownH() {
		return modelDropdownVisibleRows() * 18 + 2;
	}

	private int modelDropdownVisibleRows() {
		return Math.min(8, Math.max(1, modelOptions.size()));
	}

	private int modelIndexAt(double mouseX, double mouseY) {
		int x = modelDropdownX();
		int y = modelDropdownY();
		int w = modelDropdownW();
		int h = modelDropdownH();
		if (!inside(mouseX, mouseY, x, y, w, h)) {
			return -1;
		}
		int row = (int) ((mouseY - y - 1) / 18);
		if (row < 0 || row >= modelDropdownVisibleRows()) {
			return -1;
		}
		return modelScroll + row;
	}

	private void updateProviderButtons() {
		if (openAiButton == null || claudeButton == null) {
			return;
		}
		openAiButton.setMessage(Component.literal(providerId.equals("openai") ? "[OpenAI]" : "OpenAI"));
		claudeButton.setMessage(Component.literal(providerId.equals("claude") ? "[Claude]" : "Claude"));
		updateModelButton();
	}

	private void updateResolutionButtons() {
		for (Button button : resolutionButtons) {
			String raw = button.getMessage().getString().replace("[", "").replace("]", "");
			int size = Integer.parseInt(raw);
			button.setMessage(Component.literal(size == ClientAgentUiState.raycastSize() ? "[" + size + "]" : Integer.toString(size)));
		}
	}

	private void updateRaycastModeButton() {
		if (raycastModeButton != null) {
			raycastModeButton.setMessage(Component.literal("Mode: " + ClientAgentUiState.raycastMode().id()));
		}
	}

	private void updateDynamicButtons() {
		if (continueButton != null) {
			continueButton.active = ClientAgentUiState.awaitingApproval();
		}
		if (expandSandboxButton != null) {
			expandSandboxButton.active = ClientAgentUiState.awaitingSandboxExpansion();
		}
		if (rejectSandboxButton != null) {
			rejectSandboxButton.active = ClientAgentUiState.awaitingSandboxExpansion();
		}
		updateSandboxModeButton();
	}

	private void updateSandboxModeButton() {
		if (sandboxModeButton != null) {
			sandboxModeButton.setMessage(Component.literal("Sandbox: " + sandboxModeLabel(ClientAgentUiState.sandboxPermissionMode())));
		}
	}

	private static String nextSandboxMode(String current) {
		return switch (current) {
			case "manual_expand" -> "auto_expand_air";
			case "auto_expand_air" -> "strict";
			default -> "manual_expand";
		};
	}

	private static String sandboxModeLabel(String mode) {
		return switch (mode) {
			case "manual_expand" -> "ask";
			case "auto_expand_air" -> "auto-air";
			default -> "strict";
		};
	}

	private String normalizedProvider() {
		String normalized = providerId.toLowerCase(Locale.ROOT).trim();
		return normalized.equals("claude") ? "claude" : "openai";
	}

	private List<Line> wrappedLogLines(int width) {
		List<Line> lines = new ArrayList<>();
		List<ClientAgentUiState.LogEntry> entries = ClientAgentUiState.logs();
		for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
			ClientAgentUiState.LogEntry entry = entries.get(entryIndex);
			int color = levelColor(entry.level());
			String marker = entry.hasDetail() ? (entry.expanded() ? "- " : "+ ") : "  ";
			String prefix = marker + entry.time() + " [" + entry.level() + "] ";
			List<FormattedCharSequence> wrapped = font.split(Component.literal(prefix + entry.message()), width);
			for (int i = 0; i < wrapped.size(); i++) {
				lines.add(new Line(wrapped.get(i), color, entryIndex, entry.hasDetail() && i == 0));
			}
			if (entry.hasDetail() && entry.expanded()) {
				for (String rawLine : entry.detail().split("\\R", -1)) {
					String detailLine = "    " + rawLine;
					List<FormattedCharSequence> wrappedDetail = font.split(Component.literal(detailLine), Math.max(20, width - 10));
					if (wrappedDetail.isEmpty()) {
						lines.add(new Line(FormattedCharSequence.forward("    ", Style.EMPTY), SUBTLE, entryIndex, false));
					}
					for (FormattedCharSequence line : wrappedDetail) {
						lines.add(new Line(line, SUBTLE, entryIndex, false));
					}
				}
			}
		}
		return lines;
	}

	private LogTextArea logTextArea() {
		int bodyX = logX + 10;
		int bodyY = logY + 26;
		int bodyW = logW - 20;
		int bodyH = logH - 36;
		if ((ClientAgentUiState.gpuCaptureResult() != null || ClientAgentUiState.raycastResult() != null) && bodyH > 220) {
			int imageAreaH = Math.min(260, bodyH / 2 + 64);
			bodyY += imageAreaH + GAP;
			bodyH -= imageAreaH + GAP;
		}
		return new LogTextArea(bodyX, bodyY, bodyW, bodyH);
	}

	private int levelColor(String level) {
		return switch (level) {
			case "ok" -> OK;
			case "warn" -> WARN;
			case "error" -> ERROR;
			case "model" -> ACCENT;
			case "tool" -> UI;
			case "trace" -> MUTED;
			case "ui" -> UI;
			default -> TEXT;
		};
	}

	private int statusColor() {
		return ClientAgentUiState.status().contains("running") ? OK : MUTED;
	}

	private void drawPanel(GuiGraphics graphics, int x, int y, int w, int h, int fill, int edge) {
		graphics.fill(x, y, x + w, y + h, fill);
		graphics.fill(x, y, x + w, y + 1, edge);
		graphics.fill(x, y + h - 1, x + w, y + h, 0x88323A46);
		graphics.fill(x, y, x + 1, y + h, edge);
		graphics.fill(x + w - 1, y, x + w, y + h, 0x88323A46);
	}

	private void drawBadge(GuiGraphics graphics, int x, int y, int w, String label, int color) {
		drawBadge(graphics, x, y, w, label, color, false);
	}

	private void drawBadge(GuiGraphics graphics, int x, int y, int w, String label, int color, boolean clampText) {
		graphics.fill(x, y, x + w, y + 16, 0xAA0D1117);
		graphics.fill(x, y, x + 2, y + 16, color);
		String text = clampText && font.width(label) > w - 10 ? font.plainSubstrByWidth(label, w - 12) : label;
		graphics.drawString(font, text, x + 6, y + 4, color, false);
	}

	private boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	private record Line(FormattedCharSequence text, int color, int entryIndex, boolean toggle) {
	}

	private record LogTextArea(int x, int y, int w, int h) {
	}
}
