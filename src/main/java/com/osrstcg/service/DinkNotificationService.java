package com.osrstcg.service;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.util.PullNotificationMessages;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * Sends pack-pull webhook requests to the <a href="https://github.com/pajlads/DinkPlugin">Dink</a> plugin.
 * Requires Dink's {@code External Plugin Requests > Enable External Plugin Notifications}.
 */
@Slf4j
@Singleton
public class DinkNotificationService
{
	private static final String DINK_NAMESPACE = "dink";
	private static final String DINK_NOTIFY = "notify";
	private static final String SOURCE_PLUGIN = "OSRS TCG";
	private static final String EMBED_TITLE = "OSRS TCG";

	private final EventBus eventBus;
	private final CardDatabase cardDatabase;
	private final WikiImageCacheService wikiImageCacheService;
	private final TcgPublicStatsCalculator tcgPublicStatsCalculator;
	private final TcgChatStatsShareService tcgChatStatsShareService;

	@Inject
	DinkNotificationService(
		EventBus eventBus,
		CardDatabase cardDatabase,
		WikiImageCacheService wikiImageCacheService,
		TcgPublicStatsCalculator tcgPublicStatsCalculator,
		TcgChatStatsShareService tcgChatStatsShareService)
	{
		this.eventBus = eventBus;
		this.cardDatabase = cardDatabase;
		this.wikiImageCacheService = wikiImageCacheService;
		this.tcgPublicStatsCalculator = tcgPublicStatsCalculator;
		this.tcgChatStatsShareService = tcgChatStatsShareService;
	}

	public void notifyPackPull(String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		String trimmed = cardName.trim();
		String tierLabel = tier == null ? "" : tier.getLabel();
		String imageUrl = resolveCardImageUrl(trimmed);

		Map<String, Object> data = new HashMap<>();
		data.put("sourcePlugin", SOURCE_PLUGIN);
		data.put("text", messageWithStatsLine(
			PullNotificationMessages.dinkCollectionMessage(trimmed, newForCollection, foil)));
		data.put("title", EMBED_TITLE);
		data.put("imageRequested", true);
		if (!imageUrl.isEmpty())
		{
			data.put("thumbnail", imageUrl);
		}
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("cardName", trimmed);
		metadata.put("foil", foil);
		metadata.put("newForCollection", newForCollection);
		metadata.put("rarityTier", tierLabel);
		if (!imageUrl.isEmpty())
		{
			metadata.put("imageUrl", imageUrl);
		}
		data.put("metadata", metadata);

		try
		{
			eventBus.post(new PluginMessage(DINK_NAMESPACE, DINK_NOTIFY, data));
		}
		catch (Exception ex)
		{
			log.debug("Failed to post Dink notification", ex);
		}
	}

	private String messageWithStatsLine(String message)
	{
		return message + "\n\n" + tcgChatStatsShareService.buildPlainLine(tcgPublicStatsCalculator.computeLive());
	}

	private String resolveCardImageUrl(String cardName)
	{
		return cardDatabase.findByName(cardName)
			.map(CardDefinition::getImageUrl)
			.map(wikiImageCacheService::publicImageUrl)
			.orElse("");
	}
}
