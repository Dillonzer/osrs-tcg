package com.osrstcg.debug.catalogedit;

import com.osrstcg.service.TcgStateService;
import com.google.inject.name.Named;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Whether developer catalog-edit features (album context menu, quick sell) are active. */
@Singleton
public final class DebugCardEditGate
{
	private final TcgStateService stateService;
	private final boolean runeliteDeveloperMode;

	@Inject
	DebugCardEditGate(TcgStateService stateService, @Named("developerMode") boolean runeliteDeveloperMode)
	{
		this.stateService = stateService;
		this.runeliteDeveloperMode = runeliteDeveloperMode;
	}

	public boolean isEnabled()
	{
		return runeliteDeveloperMode && stateService.isDebugLogging();
	}
}
