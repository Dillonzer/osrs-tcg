package com.osrstcg.debug.catalogedit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/**
 * Notifies UI layers to refresh after a workspace {@code Card.json} edit without creating Guice dependency cycles.
 */
@Singleton
public final class DebugCatalogRefreshBroadcaster
{
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	@Inject
	DebugCatalogRefreshBroadcaster()
	{
	}

	public void register(Runnable listener)
	{
		if (listener != null)
		{
			listeners.add(listener);
		}
	}

	void fireAfterCatalogReload()
	{
		SwingUtilities.invokeLater(() ->
		{
			for (Runnable listener : listeners)
			{
				try
				{
					listener.run();
				}
				catch (RuntimeException ex)
				{
					// keep other listeners running
				}
			}
		});
	}
}
