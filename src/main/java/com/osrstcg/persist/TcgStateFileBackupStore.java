package com.osrstcg.persist;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.osrstcg.model.TcgState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

@Singleton
@Slf4j
public class TcgStateFileBackupStore
{
	public static final int MAX_SNAPSHOT_FILES = 50;
	public static final String MASTER_FILENAME = "tcg.save";
	public static final String SAVES_INDEX_FILENAME = "saves.json";
	public static final String DEFAULT_PROFILE_DIR = "default";
	private static final Pattern HASH_FILENAME = Pattern.compile("^[a-fA-F0-9]{64}$");
	private static final Pattern PROFILE_DIR_NAME = Pattern.compile("^(?:default|[a-fA-F0-9]{64})$");

	private final ConfigManager configManager;
	private final TcgStateCodec stateCodec;
	private final Gson gson;

	@Inject
	public TcgStateFileBackupStore(
		ConfigManager configManager,
		TcgStateCodec stateCodec,
		Gson gson)
	{
		this.configManager = configManager;
		this.stateCodec = stateCodec;
		this.gson = gson;
	}

	/**
	 * Overwrites {@code tcg.save} and upserts its row in {@code saves.json}.
	 */
	public boolean writeMaster(String encodedBlob, int cardCount, long credits, TcgSaveTrigger trigger)
	{
		if (encodedBlob == null || encodedBlob.isEmpty())
		{
			return false;
		}

		String hashHex = TcgStateHash.hexOfUtf8(encodedBlob).toLowerCase(Locale.ROOT);
		if (!writeValidatedNamedFile(MASTER_FILENAME, encodedBlob, hashHex, false))
		{
			return false;
		}

		upsertMasterMetadata(
			hashHex,
			cardCount,
			credits,
			trigger == null ? TcgSaveTrigger.COLLECTION_CHANGE : trigger);
		return true;
	}

	/** @deprecated use {@link #writeMaster(String, int, long, TcgSaveTrigger)} */
	@Deprecated
	public boolean writeMaster(String encodedBlob, int cardCount, TcgSaveTrigger trigger)
	{
		return writeMaster(encodedBlob, cardCount, 0L, trigger);
	}

	/**
	 * Writes a content-addressed hash snapshot, updates {@code saves.json}, and prunes to 50 snapshots.
	 */
	public boolean writeSnapshot(String encodedBlob, int cardCount, long credits, TcgSaveTrigger trigger)
	{
		if (encodedBlob == null || encodedBlob.isEmpty())
		{
			return false;
		}

		String hashHex = TcgStateHash.hexOfUtf8(encodedBlob).toLowerCase(Locale.ROOT);
		boolean wrote = writeValidatedNamedFile(hashHex, encodedBlob, hashHex, true);
		if (!wrote)
		{
			// Duplicate valid snapshot still counts as success for metadata refresh.
			Path existing = saveDirectory().resolve(hashHex);
			if (!Files.isRegularFile(existing) || !validateSnapshotFile(existing))
			{
				return false;
			}
		}

		upsertSnapshotMetadata(
			hashHex,
			cardCount,
			credits,
			trigger == null ? TcgSaveTrigger.MANUAL : trigger);
		pruneExcessSnapshots();
		rewriteSavesIndexFromDisk();
		return true;
	}

	/** @deprecated use {@link #writeSnapshot(String, int, long, TcgSaveTrigger)} */
	@Deprecated
	public boolean writeSnapshot(String encodedBlob, int cardCount, TcgSaveTrigger trigger)
	{
		return writeSnapshot(encodedBlob, cardCount, 0L, trigger);
	}

	public Optional<TcgState> loadMaster()
	{
		Path master = saveDirectory().resolve(MASTER_FILENAME);
		return tryLoadEncodedFile(master, false);
	}

	public Optional<TcgState> loadMostRecentSnapshot()
	{
		Path dir = saveDirectory();
		if (!Files.isDirectory(dir))
		{
			return Optional.empty();
		}

		List<Path> candidates = listSnapshotFiles(dir);
		candidates.sort(Comparator
			.comparingLong((Path p) -> savedAtEpochMsForFile(p.getFileName().toString()))
			.thenComparingLong(this::lastModifiedSafe)
			.reversed());

		for (Path file : candidates)
		{
			Optional<TcgState> state = tryLoadEncodedFile(file, true);
			if (state.isPresent())
			{
				return state;
			}
		}
		return Optional.empty();
	}

	/**
	 * Loads the most recent valid hash snapshot whose filename starts with {@code prefix}
	 * (case-insensitive).
	 */
	public Optional<TcgState> loadByHashPrefix(String prefix)
	{
		if (prefix == null || prefix.isEmpty())
		{
			return Optional.empty();
		}

		String needle = prefix.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty())
		{
			return Optional.empty();
		}

		Path dir = saveDirectory();
		if (!Files.isDirectory(dir))
		{
			return Optional.empty();
		}

		List<Path> matches = new ArrayList<>();
		for (Path file : listSnapshotFiles(dir))
		{
			String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.startsWith(needle))
			{
				matches.add(file);
			}
		}

		matches.sort(Comparator
			.comparingLong((Path p) -> savedAtEpochMsForFile(p.getFileName().toString()))
			.thenComparingLong(this::lastModifiedSafe)
			.reversed());

		for (Path file : matches)
		{
			Optional<TcgState> state = tryLoadEncodedFile(file, true);
			if (state.isPresent())
			{
				return state;
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns metadata for {@code tcg.save} and retained snapshots (syncs {@code saves.json} first),
	 * ordered newest {@code savedAt} first.
	 */
	public List<TcgSaveMetadataEntry> listSaveMetadata()
	{
		return listSaveMetadata(null);
	}

	/**
	 * Same as {@link #listSaveMetadata()} for a specific backups profile directory id.
	 *
	 * @param profileDirId hashed RSProfile folder name, {@code default}, or {@code null} for current
	 */
	public List<TcgSaveMetadataEntry> listSaveMetadata(String profileDirId)
	{
		String resolved = resolveProfileDirName(profileDirId);
		if (resolved == null)
		{
			return List.of();
		}
		rewriteSavesIndexFromDisk(resolved);
		TcgSavesIndex index = readSavesIndex(resolved);
		List<TcgSaveMetadataEntry> saves = index.getSaves();
		if (saves == null || saves.isEmpty())
		{
			return List.of();
		}
		List<TcgSaveMetadataEntry> copy = new ArrayList<>(saves.size());
		for (TcgSaveMetadataEntry entry : saves)
		{
			if (entry == null || entry.getName() == null || entry.getName().isEmpty())
			{
				continue;
			}
			copy.add(new TcgSaveMetadataEntry(
				entry.getName(),
				entry.getCardCount(),
				entry.getCredits(),
				entry.getHash(),
				entry.getSavedAt(),
				entry.getTrigger()));
		}
		copy.sort(Comparator.comparingLong((TcgSaveMetadataEntry e) -> parseSavedAtEpochMs(e.getSavedAt())).reversed());
		return copy;
	}

	/**
	 * Loads {@code tcg.save} or a hash-named snapshot by exact filename from the current profile.
	 */
	public Optional<TcgState> loadByFileName(String fileName)
	{
		return loadByFileName(null, fileName);
	}

	/**
	 * Loads a save from a specific backups profile directory.
	 */
	public Optional<TcgState> loadByFileName(String profileDirId, String fileName)
	{
		if (fileName == null || fileName.isEmpty())
		{
			return Optional.empty();
		}
		String resolved = resolveProfileDirName(profileDirId);
		if (resolved == null)
		{
			return Optional.empty();
		}
		String name = fileName.trim();
		Path dir = saveDirectory(resolved);
		if (MASTER_FILENAME.equalsIgnoreCase(name))
		{
			return tryLoadEncodedFile(dir.resolve(MASTER_FILENAME), false);
		}
		if (!HASH_FILENAME.matcher(name).matches())
		{
			return Optional.empty();
		}
		return tryLoadEncodedFile(dir.resolve(name.toLowerCase(Locale.ROOT)), true);
	}

	/** Current backups folder id ({@code default} or 64-char hex of RSProfile key). */
	public String currentProfileDirName()
	{
		String profileKey = configManager == null ? null : configManager.getRSProfileKey();
		if (profileKey == null || profileKey.isEmpty())
		{
			return DEFAULT_PROFILE_DIR;
		}
		return TcgStateHash.hexOfUtf8(profileKey).toLowerCase(Locale.ROOT);
	}

	/**
	 * Lists profile directories under {@code backups/} that look like save folders.
	 * Current profile is first when present.
	 */
	public List<TcgBackupProfile> listBackupProfiles()
	{
		String current = currentProfileDirName();
		Path root = backupsRoot();
		List<TcgBackupProfile> profiles = new ArrayList<>();
		Set<String> seen = new HashSet<>();

		if (Files.isDirectory(root))
		{
			try (var stream = Files.list(root))
			{
				stream.filter(Files::isDirectory)
					.map(path -> path.getFileName().toString())
					.filter(this::isSafeProfileDirName)
					.sorted(Comparator.naturalOrder())
					.forEach(id ->
					{
						String normalized = id.toLowerCase(Locale.ROOT);
						if (DEFAULT_PROFILE_DIR.equalsIgnoreCase(id))
						{
							normalized = DEFAULT_PROFILE_DIR;
						}
						if (seen.add(normalized))
						{
							profiles.add(new TcgBackupProfile(normalized, normalized.equals(current)));
						}
					});
			}
			catch (IOException ex)
			{
				log.debug("OSRS TCG failed to list backup profiles", ex);
			}
		}

		if (seen.add(current))
		{
			profiles.add(0, new TcgBackupProfile(current, true));
		}
		else
		{
			profiles.sort(Comparator
				.comparing((TcgBackupProfile p) -> !p.isCurrent())
				.thenComparing(TcgBackupProfile::getId));
		}
		return profiles;
	}

	/** @deprecated use {@link #loadMostRecentSnapshot()} */
	@Deprecated
	public Optional<TcgState> loadMostRecentValid()
	{
		return loadMostRecentSnapshot();
	}

	Path backupsRoot()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "backups");
	}

	Path saveDirectory()
	{
		return saveDirectory(null);
	}

	Path saveDirectory(String profileDirId)
	{
		String dirName = resolveProfileDirName(profileDirId);
		if (dirName == null)
		{
			dirName = currentProfileDirName();
		}
		return backupsRoot().resolve(dirName);
	}

	private String resolveProfileDirName(String profileDirId)
	{
		if (profileDirId == null || profileDirId.isBlank())
		{
			return currentProfileDirName();
		}
		String trimmed = profileDirId.trim();
		if (DEFAULT_PROFILE_DIR.equalsIgnoreCase(trimmed))
		{
			return DEFAULT_PROFILE_DIR;
		}
		if (!isSafeProfileDirName(trimmed))
		{
			return null;
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}

	private boolean isSafeProfileDirName(String name)
	{
		return name != null && PROFILE_DIR_NAME.matcher(name).matches();
	}

	/** @deprecated use {@link #saveDirectory()} */
	@Deprecated
	Path backupDirectory()
	{
		return saveDirectory();
	}

	private boolean writeValidatedNamedFile(String filename, String encodedBlob, String expectedHash, boolean requireHashName)
	{
		if (filename == null || filename.isEmpty() || encodedBlob == null || encodedBlob.isEmpty())
		{
			return false;
		}

		try
		{
			Path dir = saveDirectory();
			Files.createDirectories(dir);

			Path target = dir.resolve(filename);
			if (requireHashName && Files.isRegularFile(target) && validateSnapshotFile(target))
			{
				return true;
			}

			Path temp = Files.createTempFile(dir, "tcg-save-", ".tmp");
			try
			{
				Files.writeString(temp, encodedBlob, StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

				String readBack = Files.readString(temp, StandardCharsets.UTF_8);
				if (!Objects.equals(encodedBlob, readBack))
				{
					log.warn("OSRS TCG save verification failed: read-back payload mismatch.");
					return false;
				}

				String readHash = TcgStateHash.hexOfUtf8(readBack);
				if (!readHash.equalsIgnoreCase(expectedHash))
				{
					log.warn("OSRS TCG save verification failed: hash mismatch after write.");
					return false;
				}

				if (tryParseEncodedBlob(readBack).isEmpty())
				{
					log.warn("OSRS TCG save verification failed: state could not be decoded.");
					return false;
				}

				moveAtomically(temp, target);

				if (requireHashName)
				{
					if (!validateSnapshotFile(target))
					{
						log.warn("OSRS TCG snapshot verification failed after commit.");
						Files.deleteIfExists(target);
						return false;
					}
				}
				else if (!validateMasterFile(target, expectedHash))
				{
					log.warn("OSRS TCG master save verification failed after commit.");
					Files.deleteIfExists(target);
					return false;
				}

				log.debug("OSRS TCG wrote save file {}", target.getFileName());
				return true;
			}
			finally
			{
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex)
		{
			log.warn("OSRS TCG failed to write save file {}", filename, ex);
			return false;
		}
	}

	private boolean validateMasterFile(Path file, String expectedHash)
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return false;
		}
		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			if (expectedHash != null && !expectedHash.equalsIgnoreCase(TcgStateHash.hexOfUtf8(encoded)))
			{
				return false;
			}
			return tryParseEncodedBlob(encoded).isPresent();
		}
		catch (IOException ex)
		{
			return false;
		}
	}

	boolean validateSnapshotFile(Path file)
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return false;
		}

		String filename = file.getFileName().toString();
		if (!HASH_FILENAME.matcher(filename).matches())
		{
			return false;
		}

		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			if (!filename.equalsIgnoreCase(TcgStateHash.hexOfUtf8(encoded)))
			{
				return false;
			}
			return tryParseEncodedBlob(encoded).isPresent();
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG snapshot validation failed for {}", file, ex);
			return false;
		}
	}

	private Optional<TcgState> tryLoadEncodedFile(Path file, boolean requireHashName)
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return Optional.empty();
		}

		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			if (requireHashName)
			{
				if (!validateSnapshotFile(file))
				{
					return Optional.empty();
				}
			}
			else
			{
				String hash = TcgStateHash.hexOfUtf8(encoded);
				if (!validateMasterFile(file, hash))
				{
					return Optional.empty();
				}
			}
			return tryParseEncodedBlob(encoded);
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG failed to read save file {}", file, ex);
			return Optional.empty();
		}
	}

	private Optional<TcgState> tryParseEncodedBlob(String encoded)
	{
		String json = TcgStateStorageEncoding.decode(encoded);
		if (json.isEmpty())
		{
			return Optional.empty();
		}
		return stateCodec.tryFromJson(json);
	}

	private void upsertMasterMetadata(String hashHex, int cardCount, long credits, TcgSaveTrigger trigger)
	{
		TcgSavesIndex index = readSavesIndex();
		List<TcgSaveMetadataEntry> entries = new ArrayList<>(index.getSaves());
		entries.removeIf(e -> e != null && MASTER_FILENAME.equalsIgnoreCase(nullToEmpty(e.getName())));
		entries.add(0, new TcgSaveMetadataEntry(
			MASTER_FILENAME,
			Math.max(0, cardCount),
			credits,
			hashHex,
			Instant.now().toString(),
			trigger.name()));
		index.setSaves(trimToMasterAndSnapshots(entries));
		writeSavesIndex(index);
	}

	private void upsertSnapshotMetadata(String hashHex, int cardCount, long credits, TcgSaveTrigger trigger)
	{
		TcgSavesIndex index = readSavesIndex();
		List<TcgSaveMetadataEntry> entries = new ArrayList<>(index.getSaves());
		entries.removeIf(e -> e != null && hashHex.equalsIgnoreCase(nullToEmpty(e.getName())));
		entries.add(new TcgSaveMetadataEntry(
			hashHex,
			Math.max(0, cardCount),
			credits,
			hashHex,
			Instant.now().toString(),
			trigger.name()));
		index.setSaves(trimToMasterAndSnapshots(entries));
		writeSavesIndex(index);
	}

	private void pruneExcessSnapshots()
	{
		Path dir = saveDirectory();
		if (!Files.isDirectory(dir))
		{
			return;
		}

		List<Path> files = listSnapshotFiles(dir);
		if (files.size() <= MAX_SNAPSHOT_FILES)
		{
			return;
		}

		files.sort(Comparator
			.comparingLong((Path p) -> savedAtEpochMsForFile(p.getFileName().toString()))
			.thenComparingLong(this::lastModifiedSafe)
			.reversed());

		for (int i = MAX_SNAPSHOT_FILES; i < files.size(); i++)
		{
			try
			{
				Files.deleteIfExists(files.get(i));
			}
			catch (IOException ex)
			{
				log.debug("OSRS TCG failed to delete excess snapshot {}", files.get(i), ex);
			}
		}
	}

	/**
	 * Ensures {@code saves.json} only lists {@code tcg.save} (if present) and up to 50 existing snapshots.
	 */
	void rewriteSavesIndexFromDisk()
	{
		rewriteSavesIndexFromDisk(null);
	}

	void rewriteSavesIndexFromDisk(String profileDirId)
	{
		String resolved = resolveProfileDirName(profileDirId);
		if (resolved == null)
		{
			return;
		}
		Path dir = saveDirectory(resolved);
		TcgSavesIndex existing = readSavesIndex(resolved);
		List<TcgSaveMetadataEntry> previous = existing.getSaves() == null ? List.of() : existing.getSaves();

		List<TcgSaveMetadataEntry> next = new ArrayList<>();
		Path master = dir.resolve(MASTER_FILENAME);
		if (Files.isRegularFile(master))
		{
			Optional<TcgSaveMetadataEntry> priorMaster = previous.stream()
				.filter(e -> e != null && MASTER_FILENAME.equalsIgnoreCase(nullToEmpty(e.getName())))
				.findFirst();
			try
			{
				String encoded = Files.readString(master, StandardCharsets.UTF_8);
				String hashHex = TcgStateHash.hexOfUtf8(encoded).toLowerCase(Locale.ROOT);
				Optional<TcgState> parsed = tryParseEncodedBlob(encoded);
				int cardCount = priorMaster.map(TcgSaveMetadataEntry::getCardCount).orElse(0);
				long credits = priorMaster.map(TcgSaveMetadataEntry::getCredits).orElse(0L);
				if (parsed.isPresent())
				{
					cardCount = parsed.get().getCollectionState().getOwnedInstances().size();
					credits = parsed.get().getEconomyState().getCredits();
				}
				String savedAt = priorMaster.map(TcgSaveMetadataEntry::getSavedAt).orElse(null);
				if (savedAt == null || savedAt.isEmpty())
				{
					savedAt = Instant.ofEpochMilli(lastModifiedSafe(master)).toString();
				}
				String trigger = priorMaster.map(TcgSaveMetadataEntry::getTrigger).orElse(TcgSaveTrigger.UNKNOWN.name());
				next.add(new TcgSaveMetadataEntry(MASTER_FILENAME, cardCount, credits, hashHex, savedAt, trigger));
			}
			catch (IOException ex)
			{
				log.debug("OSRS TCG failed to index master save", ex);
			}
		}

		List<Path> snapshots = listSnapshotFiles(dir);
		snapshots.sort(Comparator
			.comparingLong((Path p) -> savedAtEpochMsForFile(resolved, p.getFileName().toString()))
			.thenComparingLong(this::lastModifiedSafe)
			.reversed());

		Set<String> seen = new HashSet<>();
		int snapshotCount = 0;
		for (Path file : snapshots)
		{
			if (snapshotCount >= MAX_SNAPSHOT_FILES)
			{
				break;
			}
			String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
			if (!seen.add(name) || !validateSnapshotFile(file))
			{
				continue;
			}
			Optional<TcgSaveMetadataEntry> prior = previous.stream()
				.filter(e -> e != null && name.equalsIgnoreCase(nullToEmpty(e.getName())))
				.findFirst();
			int cardCount = prior.map(TcgSaveMetadataEntry::getCardCount).orElse(0);
			long credits = prior.map(TcgSaveMetadataEntry::getCredits).orElse(0L);
			Optional<TcgState> loaded = tryLoadEncodedFile(file, true);
			if (loaded.isPresent())
			{
				cardCount = loaded.get().getCollectionState().getOwnedInstances().size();
				credits = loaded.get().getEconomyState().getCredits();
			}
			String savedAt = prior.map(TcgSaveMetadataEntry::getSavedAt).orElse(null);
			if (savedAt == null || savedAt.isEmpty())
			{
				savedAt = Instant.ofEpochMilli(lastModifiedSafe(file)).toString();
			}
			String trigger = prior.map(TcgSaveMetadataEntry::getTrigger).orElse(TcgSaveTrigger.UNKNOWN.name());
			next.add(new TcgSaveMetadataEntry(name, cardCount, credits, name, savedAt, trigger));
			snapshotCount++;
		}

		TcgSavesIndex index = new TcgSavesIndex();
		index.setSaves(next);
		writeSavesIndex(resolved, index);
	}

	private List<TcgSaveMetadataEntry> trimToMasterAndSnapshots(List<TcgSaveMetadataEntry> entries)
	{
		TcgSaveMetadataEntry master = null;
		List<TcgSaveMetadataEntry> snapshots = new ArrayList<>();
		for (TcgSaveMetadataEntry entry : entries)
		{
			if (entry == null || entry.getName() == null)
			{
				continue;
			}
			if (MASTER_FILENAME.equalsIgnoreCase(entry.getName()))
			{
				master = entry;
			}
			else if (HASH_FILENAME.matcher(entry.getName()).matches())
			{
				snapshots.add(entry);
			}
		}
		snapshots.sort(Comparator.comparingLong((TcgSaveMetadataEntry e) -> parseSavedAtEpochMs(e.getSavedAt())).reversed());
		if (snapshots.size() > MAX_SNAPSHOT_FILES)
		{
			snapshots = new ArrayList<>(snapshots.subList(0, MAX_SNAPSHOT_FILES));
		}
		List<TcgSaveMetadataEntry> out = new ArrayList<>();
		if (master != null)
		{
			out.add(master);
		}
		out.addAll(snapshots);
		return out;
	}

	private TcgSavesIndex readSavesIndex()
	{
		return readSavesIndex(null);
	}

	private TcgSavesIndex readSavesIndex(String profileDirId)
	{
		String resolved = resolveProfileDirName(profileDirId);
		if (resolved == null)
		{
			return new TcgSavesIndex();
		}
		Path path = saveDirectory(resolved).resolve(SAVES_INDEX_FILENAME);
		if (!Files.isRegularFile(path))
		{
			return new TcgSavesIndex();
		}
		try
		{
			String raw = Files.readString(path, StandardCharsets.UTF_8);
			TcgSavesIndex parsed = gson.fromJson(raw, TcgSavesIndex.class);
			TcgSavesIndex index = parsed == null ? new TcgSavesIndex() : parsed;
			normalizeEntryNames(index);
			return index;
		}
		catch (IOException | JsonSyntaxException ex)
		{
			log.debug("OSRS TCG failed to read saves.json", ex);
			return new TcgSavesIndex();
		}
	}

	private void writeSavesIndex(TcgSavesIndex index)
	{
		writeSavesIndex(null, index);
	}

	private void writeSavesIndex(String profileDirId, TcgSavesIndex index)
	{
		String resolved = resolveProfileDirName(profileDirId);
		if (resolved == null)
		{
			return;
		}
		try
		{
			Path dir = saveDirectory(resolved);
			Files.createDirectories(dir);
			Path target = dir.resolve(SAVES_INDEX_FILENAME);
			Path temp = Files.createTempFile(dir, "tcg-saves-", ".tmp");
			try
			{
				TcgSavesIndex toWrite = index == null ? new TcgSavesIndex() : index;
				normalizeEntryNames(toWrite);
				String json = gson.toJson(toWrite);
				Files.writeString(temp, json, StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
				moveAtomically(temp, target);
			}
			finally
			{
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex)
		{
			log.warn("OSRS TCG failed to write saves.json", ex);
		}
	}

	/** Promotes legacy {@code file} to {@code name} and drops the legacy field for rewrite. */
	private static void normalizeEntryNames(TcgSavesIndex index)
	{
		if (index == null || index.getSaves() == null)
		{
			return;
		}
		for (TcgSaveMetadataEntry entry : index.getSaves())
		{
			if (entry == null)
			{
				continue;
			}
			String resolved = entry.getName();
			if (resolved != null && !resolved.isEmpty())
			{
				entry.setName(resolved);
			}
		}
	}

	private long savedAtEpochMsForFile(String filename)
	{
		return savedAtEpochMsForFile(null, filename);
	}

	private long savedAtEpochMsForFile(String profileDirId, String filename)
	{
		TcgSavesIndex index = readSavesIndex(profileDirId);
		if (index.getSaves() != null)
		{
			for (TcgSaveMetadataEntry entry : index.getSaves())
			{
				if (entry != null && filename.equalsIgnoreCase(nullToEmpty(entry.getName())))
				{
					return parseSavedAtEpochMs(entry.getSavedAt());
				}
			}
		}
		String resolved = resolveProfileDirName(profileDirId);
		if (resolved == null)
		{
			return 0L;
		}
		return lastModifiedSafe(saveDirectory(resolved).resolve(filename));
	}

	private static long parseSavedAtEpochMs(String savedAt)
	{
		if (savedAt == null || savedAt.isEmpty())
		{
			return 0L;
		}
		try
		{
			return Instant.parse(savedAt).toEpochMilli();
		}
		catch (Exception ex)
		{
			return 0L;
		}
	}

	private List<Path> listSnapshotFiles(Path dir)
	{
		List<Path> files = new ArrayList<>();
		try (var stream = Files.list(dir))
		{
			stream.filter(Files::isRegularFile)
				.filter(path -> HASH_FILENAME.matcher(path.getFileName().toString()).matches())
				.forEach(files::add);
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG failed to list save directory {}", dir, ex);
		}
		return files;
	}

	private long lastModifiedSafe(Path file)
	{
		try
		{
			return Files.getLastModifiedTime(file).toMillis();
		}
		catch (IOException ex)
		{
			return 0L;
		}
	}

	private static String nullToEmpty(String value)
	{
		return value == null ? "" : value;
	}

	private static void moveAtomically(Path source, Path target) throws IOException
	{
		try
		{
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
