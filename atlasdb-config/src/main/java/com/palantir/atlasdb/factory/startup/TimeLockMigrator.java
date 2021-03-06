/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.factory.startup;

import com.palantir.async.initializer.AsyncInitializer;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.config.ServerListConfig;
import com.palantir.atlasdb.config.TimeLockClientConfig;
import com.palantir.atlasdb.factory.ServiceCreator;
import com.palantir.common.annotation.Idempotent;
import com.palantir.timestamp.TimestampManagementService;
import com.palantir.timestamp.TimestampStoreInvalidator;

@SuppressWarnings("FinalClass")
public class TimeLockMigrator extends AsyncInitializer {
    private final TimestampStoreInvalidator source;
    private final TimestampManagementService destination;
    private final boolean initializeAsync;

    private TimeLockMigrator(
            TimestampStoreInvalidator source,
            TimestampManagementService destination,
            boolean initializeAsync) {
        this.source = source;
        this.destination = destination;
        this.initializeAsync = initializeAsync;
    }

    public static TimeLockMigrator create(
            TimeLockClientConfig config,
            TimestampStoreInvalidator invalidator,
            String userAgent) {
        return create(config, invalidator, userAgent, AtlasDbConstants.DEFAULT_INITIALIZE_ASYNC);
    }

    public static TimeLockMigrator create(
            TimeLockClientConfig config,
            TimestampStoreInvalidator invalidator,
            String userAgent,
            boolean initializeAsync) {
        TimestampManagementService remoteTimestampManagementService =
                createRemoteManagementService(config, userAgent);
        return new TimeLockMigrator(invalidator, remoteTimestampManagementService, initializeAsync);
    }

    /**
     * Migration works as follows:
     * 1. Ping the destination Timelock Server. If this fails, throw.
     * 2. Backup and invalidate the Timestamp Bound, returning TS.
     * At this point, the database should contain an invalidated timestamp bound, plus a backup bound of TS.
     * 3. Fast-forward the destination to TS.
     * <p>
     * The purpose of step 1 is largely to handle cases where users accidentally mis-configure their AtlasDB clients to
     * attempt to talk to Timelock; we want to ensure there's a legitimate Timelock Server present before doing the
     * invalidation. In the event of a failure between steps 2 and 3, rerunning this method is safe, because
     * backupAndInvalidate()'s contract is that it returns the timestamp bound that was backed up if the current bound
     * is unreadable.
     */
    @Idempotent
    public void migrate() {
        initialize(initializeAsync);
    }

    private static TimestampManagementService createRemoteManagementService(
            TimeLockClientConfig timelockConfig,
            String userAgent) {
        ServerListConfig serverListConfig = timelockConfig.toNamespacedServerList();
        return new ServiceCreator<>(TimestampManagementService.class, userAgent)
                .apply(serverListConfig);
    }

    @Override
    @SuppressWarnings({"CheckReturnValue", "ResultOfMethodCallIgnored"}) // errorprone doesn't pick up "when=NEVER"
    protected synchronized void tryInitialize() {
        try {
            destination.ping();
        } catch (Exception e) {
            throw new IllegalStateException("Could not contact the Timelock Server.", e);
        }
        long currentTimestamp = source.backupAndInvalidate();
        destination.fastForwardTimestamp(currentTimestamp);
    }

    @Override
    protected String getInitializingClassName() {
        return TimeLockMigrator.class.getSimpleName();
    }
}

