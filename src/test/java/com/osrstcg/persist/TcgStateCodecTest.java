package com.osrstcg.persist;

import com.google.gson.Gson;
import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.SkillCreditBaseline;
import com.osrstcg.model.TcgState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies JSON schema load/upgrade for every profile schema shape supported since 0.16.2
 * (schema 5 with {@code cardInstances}) through current schema 6 ({@code cardEntries}),
 * plus older shapes the codec still accepts.
 */
public class TcgStateCodecTest
{
	private final TcgStateCodec codec = new TcgStateCodec(new Gson());

	/** Minimal schema-5 blob as written around 0.16.2 / commit a8be0f06. */
	private static final String SCHEMA_5_MINIMAL = ""
		+ "{"
		+ "\"schemaVersion\":5,"
		+ "\"credits\":1200,"
		+ "\"openedPacks\":3,"
		+ "\"cardInstances\":["
		+ "{\"id\":\"abc\",\"cardName\":\"Abyssal whip\",\"foil\":false,\"pulledBy\":\"Player\",\"pulledAt\":1710000000000,\"locked\":false},"
		+ "{\"id\":\"def\",\"cardName\":\"Abyssal whip\",\"foil\":true,\"pulledBy\":\"Player\",\"pulledAt\":1710000010000,\"locked\":true}"
		+ "],"
		+ "\"foilChancePercent\":5,"
		+ "\"killCreditMultiplier\":1.0,"
		+ "\"levelUpCreditMultiplier\":1.0,"
		+ "\"xpCreditMultiplier\":1.0,"
		+ "\"debugLogging\":false,"
		+ "\"packRevealOverlayScale\":1.0,"
		+ "\"albumWindowWidth\":800,"
		+ "\"albumWindowHeight\":600,"
		+ "\"skillCreditBaseline\":{\"skillXp\":{\"Attack\":1000,\"Cooking\":55000},\"uncreditedXp\":250},"
		+ "\"totalCreditsGained\":5000,"
		+ "\"profileCreatedAtUnix\":1700000000,"
		+ "\"profileSavedAtUnix\":1700000100"
		+ "}";

	/** Full schema-5 with empty skill baseline placeholder (upgrade rewrite shape). */
	private static final String SCHEMA_5_EMPTY_BASELINE = ""
		+ "{"
		+ "\"schemaVersion\":5,"
		+ "\"credits\":10,"
		+ "\"openedPacks\":0,"
		+ "\"cardInstances\":[],"
		+ "\"skillCreditBaseline\":{\"skillXp\":{},\"uncreditedXp\":0},"
		+ "\"totalCreditsGained\":10,"
		+ "\"profileCreatedAtUnix\":1700000000,"
		+ "\"profileSavedAtUnix\":0"
		+ "}";

	/** Schema 5 missing the schema-5 meta fields (partial upgrade / older write). */
	private static final String SCHEMA_5_MISSING_META = ""
		+ "{"
		+ "\"schemaVersion\":5,"
		+ "\"credits\":42,"
		+ "\"openedPacks\":1,"
		+ "\"cardInstances\":["
		+ "{\"cardName\":\"Rune scimitar\",\"foil\":false,\"pulledBy\":\"A\",\"pulledAt\":100}"
		+ "]"
		+ "}";

	@Test
	public void loadsSchema5From0162AndUpgradesToCurrentOnWrite()
	{
		TcgState loaded = codec.fromJson(SCHEMA_5_MINIMAL);
		Assert.assertEquals(TcgState.CURRENT_SCHEMA_VERSION, loaded.getSchemaVersion());
		Assert.assertEquals(1200L, loaded.getEconomyState().getCredits());
		Assert.assertEquals(3L, loaded.getEconomyState().getOpenedPacks());
		Assert.assertEquals(2, loaded.getCollectionState().getOwnedInstances().size());
		Assert.assertEquals("abc", loaded.getCollectionState().getOwnedInstances().get(0).getInstanceId());
		Assert.assertTrue(loaded.getCollectionState().getOwnedInstances().get(1).isFoil());
		Assert.assertTrue(loaded.getCollectionState().getOwnedInstances().get(1).isLocked());
		Assert.assertEquals(5000L, loaded.getTotalCreditsGained());
		Assert.assertEquals(1_700_000_000L, loaded.getProfileCreatedAtUnix());
		Assert.assertEquals(1_700_000_100L, loaded.getProfileSavedAtUnix());
		Assert.assertTrue(loaded.getSkillCreditBaseline().isPresent());
		Assert.assertEquals(250L, loaded.getSkillCreditBaseline().getUncreditedXp());
		Assert.assertEquals(1000, loaded.getSkillCreditBaseline().xpFor(Skill.ATTACK).orElse(-1));
		Assert.assertEquals(55_000, loaded.getSkillCreditBaseline().xpFor(Skill.COOKING).orElse(-1));

		String upgraded = codec.toJson(loaded);
		Assert.assertTrue(upgraded.contains("\"schemaVersion\":6") || upgraded.contains("\"schemaVersion\": 6"));
		Assert.assertTrue(upgraded.contains("cardEntries"));
		Assert.assertFalse(upgraded.contains("cardInstances"));

		TcgState roundTrip = codec.fromJson(upgraded);
		Assert.assertEquals(2, roundTrip.getCollectionState().getOwnedInstances().size());
		Assert.assertEquals(1200L, roundTrip.getEconomyState().getCredits());
		Assert.assertEquals(5000L, roundTrip.getTotalCreditsGained());
		Map<CardCollectionKey, Integer> owned = roundTrip.getCollectionState().getOwnedCards();
		Assert.assertEquals(1, owned.get(new CardCollectionKey("Abyssal whip", false)).intValue());
		Assert.assertEquals(1, owned.get(new CardCollectionKey("Abyssal whip", true)).intValue());
	}

	@Test
	public void loadsSchema5EmptyBaselineWithoutUpgradeFlag()
	{
		TcgState loaded = codec.fromJson(SCHEMA_5_EMPTY_BASELINE);
		Assert.assertFalse(loaded.getSkillCreditBaseline().needsSchemaUpgradePersist());
		Assert.assertFalse(loaded.getSkillCreditBaseline().isPresent());
		Assert.assertEquals(10L, loaded.getTotalCreditsGained());
	}

	@Test
	public void loadsSchema5MissingMetaFields()
	{
		TcgState loaded = codec.fromJson(SCHEMA_5_MISSING_META);
		Assert.assertEquals(TcgState.CURRENT_SCHEMA_VERSION, loaded.getSchemaVersion());
		Assert.assertEquals(42L, loaded.getEconomyState().getCredits());
		Assert.assertEquals(1, loaded.getCollectionState().getOwnedInstances().size());
		Assert.assertEquals(0L, loaded.getTotalCreditsGained());
		Assert.assertEquals(0L, loaded.getProfileCreatedAtUnix());
		Assert.assertTrue(loaded.getSkillCreditBaseline().needsSchemaUpgradePersist());
	}

	@Test
	public void loadsCurrentSchema6CardEntries()
	{
		String json = ""
			+ "{"
			+ "\"schemaVersion\":6,"
			+ "\"credits\":1200,"
			+ "\"openedPacks\":3,"
			+ "\"cardEntries\":[{"
			+ "\"cardName\":\"Abyssal whip\","
			+ "\"variants\":["
			+ "{\"pulledBy\":\"Player\",\"pulledAt\":1710000000000},"
			+ "{\"foil\":true,\"pulledBy\":\"Player\",\"pulledAt\":1710000010000,\"locked\":true}"
			+ "]"
			+ "}],"
			+ "\"skillCreditBaseline\":{\"skillXp\":{},\"uncreditedXp\":0},"
			+ "\"totalCreditsGained\":5000,"
			+ "\"profileCreatedAtUnix\":1700000000,"
			+ "\"profileSavedAtUnix\":1700000100"
			+ "}";

		TcgState loaded = codec.fromJson(json);
		Assert.assertEquals(2, loaded.getCollectionState().getOwnedInstances().size());
		Assert.assertEquals(1L, loaded.getCollectionState().getOwnedInstances().stream()
			.filter(OwnedCardInstance::isLocked).count());
		Assert.assertEquals(TcgState.CURRENT_SCHEMA_VERSION, loaded.getSchemaVersion());

		String rewritten = codec.toJson(loaded);
		Assert.assertTrue(rewritten.contains("cardEntries"));
		Assert.assertFalse(rewritten.contains("cardInstances"));
	}

	@Test
	public void schema5EncodedConfigBlobRoundTripsThroughStorageEncoding()
	{
		String blob = TcgStateStorageEncoding.encode(SCHEMA_5_MINIMAL);
		Assert.assertTrue(blob.startsWith(TcgStateStorageEncoding.STORAGE_PREFIX));
		String decoded = TcgStateStorageEncoding.decode(blob);
		TcgState loaded = codec.fromJson(decoded);
		Assert.assertEquals(2, loaded.getCollectionState().getOwnedInstances().size());
		Assert.assertEquals(1200L, loaded.getEconomyState().getCredits());

		String hash = TcgStateHash.hexOfUtf8(blob);
		Assert.assertEquals(64, hash.length());
		Assert.assertEquals(hash, TcgStateHash.hexOfUtf8(blob));
	}

	@Test
	public void fromJsonUpgradesPre0162MissingSkillBaselineAndProfileMeta()
	{
		String legacy = "{"
			+ "\"schemaVersion\":3,"
			+ "\"credits\":500,"
			+ "\"openedPacks\":1,"
			+ "\"cardInstances\":[]"
			+ "}";

		TcgState state = codec.fromJson(legacy);
		Assert.assertEquals(TcgState.CURRENT_SCHEMA_VERSION, state.getSchemaVersion());
		Assert.assertEquals(500L, state.getEconomyState().getCredits());
		Assert.assertTrue(state.getSkillCreditBaseline().needsSchemaUpgradePersist());
		Assert.assertFalse(state.getSkillCreditBaseline().isPresent());
		Assert.assertEquals(0L, state.getTotalCreditsGained());
		Assert.assertEquals(0L, state.getProfileCreatedAtUnix());

		String upgraded = codec.toJson(state.withProfileCreatedAtUnix(1_700_000_000L));
		Assert.assertTrue(upgraded.contains("\"schemaVersion\":6") || upgraded.contains("\"schemaVersion\": 6"));
		Assert.assertTrue(upgraded.contains("skillCreditBaseline"));
		Assert.assertTrue(upgraded.contains("totalCreditsGained"));
		Assert.assertTrue(upgraded.contains("profileCreatedAtUnix"));
		Assert.assertTrue(upgraded.contains("cardEntries"));
	}

	@Test
	public void roundTripsCardEntriesWithVariants()
	{
		List<OwnedCardInstance> instances = List.of(
			OwnedCardInstance.createNew("Abyssal whip", false, "Player", 1_710_000_000_000L),
			OwnedCardInstance.createNew("Abyssal whip", false, "Player", 1_710_000_010_000L),
			OwnedCardInstance.createNew("Abyssal whip", true, "Player", 1_710_000_020_000L)
		);
		TcgState state = TcgState.empty().withCollection(CollectionState.copyOf(instances));

		String json = codec.toJson(state);
		Assert.assertTrue(json.contains("cardEntries"));
		Assert.assertFalse(json.contains("cardInstances"));
		Assert.assertTrue(json.contains("\"foil\":true"));
		Assert.assertFalse(json.contains("\"foil\":false"));
		Assert.assertFalse(json.contains("quantity"));

		TcgState loaded = codec.fromJson(json);
		Map<CardCollectionKey, Integer> owned = loaded.getCollectionState().getOwnedCards();
		Assert.assertEquals(2, owned.get(new CardCollectionKey("Abyssal whip", false)).intValue());
		Assert.assertEquals(1, owned.get(new CardCollectionKey("Abyssal whip", true)).intValue());
		Assert.assertEquals(3, loaded.getCollectionState().getOwnedInstances().size());
	}

	@Test
	public void readsLegacyQuantityInVariants()
	{
		String json = "{"
			+ "\"schemaVersion\":6,"
			+ "\"credits\":0,"
			+ "\"openedPacks\":0,"
			+ "\"cardEntries\":[{\"cardName\":\"Rune scimitar\",\"variants\":["
			+ "{\"quantity\":2,\"lockedQuantity\":1,\"pulledBy\":\"Player\",\"pulledAt\":100}"
			+ "]}]"
			+ "}";

		TcgState loaded = codec.fromJson(json);
		Assert.assertEquals(2, loaded.getCollectionState().getOwnedInstances().size());
		Assert.assertEquals(1L, loaded.getCollectionState().getOwnedInstances().stream()
			.filter(OwnedCardInstance::isLocked).count());
	}

	@Test
	public void roundTripsPresentSkillBaselineBySkillName()
	{
		Map<String, Integer> xp = new LinkedHashMap<>();
		xp.put("Attack", 1000);
		xp.put("Cooking", 55_000);
		TcgState state = TcgState.empty()
			.withCredits(10L)
			.withTotalCreditsGained(1_234L)
			.withSkillCreditBaseline(SkillCreditBaseline.of(xp, 250L));

		TcgState loaded = codec.fromJson(codec.toJson(state));
		Assert.assertTrue(loaded.getSkillCreditBaseline().isPresent());
		Assert.assertEquals(250L, loaded.getSkillCreditBaseline().getUncreditedXp());
		Assert.assertEquals(1000, loaded.getSkillCreditBaseline().xpFor(Skill.ATTACK).orElse(-1));
		Assert.assertEquals(55_000, loaded.getSkillCreditBaseline().xpFor(Skill.COOKING).orElse(-1));
		Assert.assertEquals(1_234L, loaded.getTotalCreditsGained());
	}
}
