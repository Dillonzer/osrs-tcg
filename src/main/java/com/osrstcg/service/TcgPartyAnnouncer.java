package com.osrstcg.service;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.model.TcgPublicStats;
import com.osrstcg.party.TcgChatStatsPartyMessage;
import com.osrstcg.party.TcgCollectionSetCompletePartyMessage;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.party.PartyService;

/** Sends OSRS TCG party websocket payloads (collection set completion, !tcg stats). */
@Slf4j
@Singleton
public class TcgPartyAnnouncer
{
	private final PartyService partyService;
	private final OsrsTcgConfig config;

	@Inject
	public TcgPartyAnnouncer(PartyService partyService, OsrsTcgConfig config)
	{
		this.partyService = partyService;
		this.config = config;
	}

	public void announceCollectionSetComplete(String collectionDisplayName)
	{
		if (!partyAnnouncementsEnabled())
		{
			return;
		}
		if (collectionDisplayName == null || collectionDisplayName.trim().isEmpty())
		{
			return;
		}
		if (!partyService.isInParty())
		{
			return;
		}
		try
		{
			TcgCollectionSetCompletePartyMessage message = new TcgCollectionSetCompletePartyMessage();
			message.setCollectionName(collectionDisplayName.trim());
			partyService.send(message);
		}
		catch (Exception ex)
		{
			log.debug("Could not send collection set party message", ex);
		}
	}

	public void broadcastChatCommandStats(TcgPublicStats stats)
	{
		if (stats == null)
		{
			return;
		}
		if (!partyService.isInParty())
		{
			return;
		}
		try
		{
			TcgChatStatsPartyMessage message = new TcgChatStatsPartyMessage();
			message.setCollectionScore(stats.getCollectionScore());
			message.setCompletionPct(stats.getCompletionPct());
			message.setUniqueOwned(stats.getUniqueOwned());
			message.setUniqueFoilOwned(stats.getUniqueFoilOwned());
			message.setFoilCompletionPct(stats.getFoilCompletionPct());
			message.setTotalCardPool(stats.getTotalCardPool());
			message.setOpenedPacks(stats.getOpenedPacks());
			message.setTotalCardsOwned(stats.getTotalCardsOwned());
			message.setCustomRates(stats.isCustomRates());
			partyService.send(message);
		}
		catch (Exception ex)
		{
			log.debug("Could not send !tcg stats party message", ex);
		}
	}

	private boolean partyAnnouncementsEnabled()
	{
		return config.partyAnnounceMythicPulls();
	}
}
