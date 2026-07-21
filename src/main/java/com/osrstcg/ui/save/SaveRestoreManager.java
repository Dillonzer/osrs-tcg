package com.osrstcg.ui.save;

import com.osrstcg.persist.TcgBackupProfile;
import com.osrstcg.persist.TcgSaveMetadataEntry;
import com.osrstcg.service.TcgStateService;
import java.util.List;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

@Singleton
public final class SaveRestoreManager
{
	private final TcgStateService stateService;
	private volatile SaveRestoreDialog dialog;

	@Inject
	public SaveRestoreManager(TcgStateService stateService)
	{
		this.stateService = stateService;
	}

	/**
	 * Opens the restore picker on the EDT.
	 * {@code onRestoreAccepted} receives {@code (profileDirId, fileName)}.
	 */
	public void showPicker(BiConsumer<String, String> onRestoreAccepted)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (!hasAnyDiskSaves())
			{
				JOptionPane.showMessageDialog(
					null,
					"No disk saves found in any backup profile.",
					"OSRS TCG",
					JOptionPane.INFORMATION_MESSAGE);
				return;
			}

			if (dialog != null && dialog.isDisplayable())
			{
				dialog.toFront();
				return;
			}

			dialog = new SaveRestoreDialog(stateService, (profileId, fileName) ->
			{
				dialog = null;
				if (onRestoreAccepted != null)
				{
					onRestoreAccepted.accept(profileId, fileName);
				}
			});
			dialog.addWindowListener(new java.awt.event.WindowAdapter()
			{
				@Override
				public void windowClosed(java.awt.event.WindowEvent e)
				{
					dialog = null;
				}
			});
			dialog.setVisible(true);
		});
	}

	private boolean hasAnyDiskSaves()
	{
		List<TcgBackupProfile> profiles = stateService.listBackupProfiles();
		if (profiles == null || profiles.isEmpty())
		{
			return !stateService.listDiskSaves().isEmpty();
		}
		for (TcgBackupProfile profile : profiles)
		{
			List<TcgSaveMetadataEntry> saves = stateService.listDiskSaves(profile.getId());
			if (saves != null && !saves.isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	public void dispose()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (dialog != null)
			{
				dialog.dispose();
				dialog = null;
			}
		});
	}
}
