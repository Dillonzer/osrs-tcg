package com.osrstcg.util;

public final class PullNotificationMessages
{
	private PullNotificationMessages()
	{
	}

	public static String collectionMessage(String playerName, String cardName, boolean newForCollection, boolean foil)
	{
		String who = playerName == null || playerName.trim().isEmpty() ? "Unknown player" : playerName.trim();
		String card = cardName == null ? "" : cardName.trim();
		String duplicatePrefix = newForCollection ? "" : "duplicate ";
		String foilSuffix = foil ? " (foil)" : "";
		return who + " just added " + duplicatePrefix + card + foilSuffix + " to their collection!";
	}

	public static String dinkCollectionMessage(String cardName, boolean newForCollection, boolean foil)
	{
		return collectionMessage("%USERNAME%", cardName, newForCollection, foil);
	}
}
