package com.osrstcg.persist;

/**
 * A profile folder under {@code .runelite/OSRS-TCG/backups/}.
 */
public final class TcgBackupProfile
{
	private final String id;
	private final boolean current;

	public TcgBackupProfile(String id, boolean current)
	{
		this.id = id == null ? "" : id;
		this.current = current;
	}

	public String getId()
	{
		return id;
	}

	public boolean isCurrent()
	{
		return current;
	}

	/** Short label for UI dropdowns. */
	public String getDisplayLabel()
	{
		String shortId = id.length() <= 8 ? id : id.substring(0, 8);
		return current ? "Current profile (" + shortId + ")" : "Profile " + shortId;
	}

	@Override
	public String toString()
	{
		return getDisplayLabel();
	}
}
