package com.osrstcg.service;

import com.google.gson.Gson;
import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.util.PullNotificationMessages;
import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sends rarity-coloured Discord embeds to an optional user-configured webhook URL.
 */
@Slf4j
@Singleton
public class PullWebhookNotificationService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String EMBED_TITLE = "OSRS TCG";

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Client client;
	private final OsrsTcgConfig config;
	private final CardDatabase cardDatabase;
	private final WikiImageCacheService wikiImageCacheService;
	private final TcgPublicStatsCalculator tcgPublicStatsCalculator;
	private final TcgChatStatsShareService tcgChatStatsShareService;

	@Inject
	PullWebhookNotificationService(
		OkHttpClient okHttpClient,
		Gson gson,
		Client client,
		OsrsTcgConfig config,
		CardDatabase cardDatabase,
		WikiImageCacheService wikiImageCacheService,
		TcgPublicStatsCalculator tcgPublicStatsCalculator,
		TcgChatStatsShareService tcgChatStatsShareService)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.client = client;
		this.config = config;
		this.cardDatabase = cardDatabase;
		this.wikiImageCacheService = wikiImageCacheService;
		this.tcgPublicStatsCalculator = tcgPublicStatsCalculator;
		this.tcgChatStatsShareService = tcgChatStatsShareService;
	}

	public void notifyPackPull(String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier)
	{
		String webhookUrl = config.pullWebhookUrl();
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
			log.debug("Pull webhook skipped: no URL configured");
			return;
		}
		if (cardName == null || cardName.trim().isEmpty())
		{
			log.warn("Pull webhook skipped: empty card name");
			return;
		}

		String trimmed = webhookUrl.trim();
		HttpUrl parsedUrl = HttpUrl.parse(trimmed);
		if (parsedUrl == null)
		{
			log.warn("Pull webhook skipped: invalid URL {}", maskWebhookUrl(trimmed));
			return;
		}

		try
		{
			String card = cardName.trim();
			String imageUrl = resolveCardImageUrl(card);
			String playerName = resolvePlayerName();
			String description = PullNotificationMessages.collectionMessage(playerName, card, newForCollection, foil);
			String statsLine = tcgChatStatsShareService.buildPlainLine(tcgPublicStatsCalculator.computeLive());
			String payload = gson.toJson(buildPayload(description, statsLine, tier, imageUrl));

			log.info(
				"Sending pull webhook for '{}' (foil={}, new={}, tier={}) to {} (payload {} bytes, footer {} chars)",
				card,
				foil,
				newForCollection,
				tier == null ? "unknown" : tier.getLabel(),
				maskWebhookUrl(trimmed),
				payload.length(),
				statsLine.length());

			Request request = new Request.Builder()
				.url(parsedUrl)
				.post(RequestBody.create(JSON, payload))
				.build();

			okHttpClient.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					log.warn("Pull webhook request failed for '{}': {}", card, e.toString());
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					try (ResponseBody body = response.body())
					{
						if (response.isSuccessful())
						{
							log.info("Pull webhook sent for '{}' (HTTP {})", card, response.code());
							return;
						}
						String responseBody = body == null ? "" : body.string();
						log.warn(
							"Pull webhook rejected for '{}' (HTTP {}): {}",
							card,
							response.code(),
							truncateForLog(responseBody));
					}
					catch (IOException ex)
					{
						log.warn("Pull webhook response read failed for '{}': {}", card, ex.toString());
					}
				}
			});
		}
		catch (Exception ex)
		{
			log.warn("Pull webhook failed before send for '{}'", cardName.trim(), ex);
		}
	}

	private static Map<String, Object> buildPayload(
		String description, String footerText, RarityMath.Tier tier, String imageUrl)
	{
		Map<String, Object> embed = new LinkedHashMap<>();
		embed.put("title", EMBED_TITLE);
		embed.put("description", description);
		embed.put("color", discordColor(tier));
		if (footerText != null && !footerText.isEmpty())
		{
			embed.put("footer", Map.of("text", footerText));
		}
		if (imageUrl != null && !imageUrl.isEmpty())
		{
			embed.put("image", Map.of("url", imageUrl));
		}

		List<Map<String, Object>> embeds = new ArrayList<>();
		embeds.add(embed);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("embeds", embeds);
		return payload;
	}

	private static int discordColor(RarityMath.Tier tier)
	{
		Color color = tier == null ? Color.WHITE : tier.getColor();
		return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
	}

	private String resolvePlayerName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return "Unknown player";
		}
		return Text.sanitize(client.getLocalPlayer().getName());
	}

	private String resolveCardImageUrl(String cardName)
	{
		return cardDatabase.findByName(cardName)
			.map(CardDefinition::getImageUrl)
			.map(wikiImageCacheService::publicImageUrl)
			.orElse("");
	}

	private static String maskWebhookUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return "<empty>";
		}
		HttpUrl parsed = HttpUrl.parse(url.trim());
		if (parsed == null)
		{
			return "<invalid>";
		}
		return parsed.scheme() + "://" + parsed.host() + parsed.encodedPath();
	}

	private static String truncateForLog(String value)
	{
		if (value == null || value.isEmpty())
		{
			return "<empty body>";
		}
		String normalized = value.replace('\n', ' ').trim();
		return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
	}
}
