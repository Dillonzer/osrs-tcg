package com.osrstcg.overlay;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.data.BoosterPackDefinition;
import com.osrstcg.data.PackCatalog;
import com.osrstcg.service.CreditsRateTracker;
import com.osrstcg.service.TcgStateService;
import com.osrstcg.util.NumberFormatting;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.util.ImageUtil;

/**
 * Movable plain-text overlay for current credits and a short-window credits/h rate.
 * Shift+right-click lists visible booster packs to buy and open.
 */
@Singleton
public class CreditsInfoboxOverlay extends OverlayPanel
{
	public static final String MENU_OPTION_OPEN = "Open";

	private static final BufferedImage CREDIT_ICON = ImageUtil.resizeImage(
		ImageUtil.loadImageResource(CreditsInfoboxOverlay.class, "/credits.png"),
		21,
		16);

	private final OsrsTcgConfig config;
	private final TcgStateService stateService;
	private final CreditsRateTracker creditsRateTracker;
	private final PackCatalog packCatalog;

	@Inject
	CreditsInfoboxOverlay(
		OsrsTcgConfig config,
		TcgStateService stateService,
		CreditsRateTracker creditsRateTracker,
		PackCatalog packCatalog)
	{
		this.config = config;
		this.stateService = stateService;
		this.creditsRateTracker = creditsRateTracker;
		this.packCatalog = packCatalog;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.creditsInfobox())
		{
			getMenuEntries().clear();
			return null;
		}

		refreshPackMenuEntries();

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(SplitComponent.builder()
			.orientation(ComponentOrientation.HORIZONTAL)
			.gap(new Point(4, 0))
			.first(new ImageComponent(CREDIT_ICON))
			.second(LineComponent.builder()
				.right(NumberFormatting.format(stateService.getCredits()))
				.build())
			.build());

		if (config.creditsPerHour())
		{
			Long creditsPerHour = creditsRateTracker.creditsPerHourOrNull();
			if (creditsPerHour != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.right(NumberFormatting.format(creditsPerHour) + "/h")
					.build());
			}
		}

		return super.render(graphics);
	}

	/** Display name used as the overlay menu target for a pack. */
	public static String packMenuTarget(BoosterPackDefinition booster)
	{
		if (booster == null)
		{
			return "";
		}
		if (booster.getName() != null && !booster.getName().isBlank())
		{
			return booster.getName();
		}
		if (booster.getId() != null && !booster.getId().isBlank())
		{
			return booster.getId();
		}
		return "Pack";
	}

	private void refreshPackMenuEntries()
	{
		getMenuEntries().clear();
		for (BoosterPackDefinition booster : packCatalog.getVisibleBoosters(stateService.isDebugLogging()))
		{
			addMenuEntry(MenuAction.RUNELITE_OVERLAY, MENU_OPTION_OPEN, packMenuTarget(booster));
		}
	}
}
