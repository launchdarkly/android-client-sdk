package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2Change;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2ChangeType;
import com.launchdarkly.sdk.json.SerializationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates internal FDv2 changesets into the Android SDK data model format.
 * <p>
 * The Android client SDK handles flags only; segments and other server-only kinds are
 * skipped with a warning. Each PUT change is deserialized into a {@link Flag} and each
 * DELETE change is represented as a deleted-item placeholder.
 */
final class FDv2ChangeSetTranslator {
    private FDv2ChangeSetTranslator() {}

    /**
     * Converts an internal {@link FDv2ChangeSet} to an Android {@link ChangeSet}.
     *
     * @param changeset the FDv2 changeset to convert
     * @param logger    logger for diagnostic messages
     * @return a ChangeSet containing the converted flag data
     * @throws SerializationException if a PUT payload cannot be deserialized as a Flag
     */
    static ChangeSet<Map<String, Flag>> toChangeSet(
            FDv2ChangeSet changeset,
            LDLogger logger) throws SerializationException {

        ChangeSetType changeSetType;
        switch (changeset.getType()) {
            case FULL:
                changeSetType = ChangeSetType.Full;
                break;
            case PARTIAL:
                changeSetType = ChangeSetType.Partial;
                break;
            case NONE:
                changeSetType = ChangeSetType.None;
                break;
            default:
                throw new IllegalArgumentException(
                        "Unknown FDv2ChangeSetType: " + changeset.getType());
        }

        Map<String, Flag> flags = new LinkedHashMap<>();

        for (FDv2Change change : changeset.getChanges()) {
            if (!"flag_eval".equals(change.getKind())) {
                logger.debug("Skipping non-flag data kind '{}' in FDv2 changeset", change.getKind());
                continue;
            }

            Flag flag;
            if (change.getType() == FDv2ChangeType.PUT) {
                if (change.getObject() == null) {
                    logger.warn("FDv2 PUT for flag '{}' is missing object data; skipping", change.getKey());
                    continue;
                }
                flag = Flag.fromJson(change.getObject().toString());
            } else {
                flag = Flag.deletedItemPlaceholder(change.getKey(), change.getVersion());
            }

            flags.put(change.getKey(), flag);
        }

        Selector selector = changeset.getSelector() != null
                ? changeset.getSelector()
                : Selector.EMPTY;

        return new ChangeSet<>(changeSetType, selector, flags, null, true);
    }
}
