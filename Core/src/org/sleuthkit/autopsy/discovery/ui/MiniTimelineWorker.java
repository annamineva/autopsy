/*
 * Autopsy
 *
 * Copyright 2020 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.discovery.ui;

import org.sleuthkit.autopsy.discovery.search.MiniTimelineResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import javax.swing.SwingWorker;
import org.apache.commons.lang3.StringUtils;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.discovery.search.DiscoveryEventUtils;
import org.sleuthkit.autopsy.discovery.search.DiscoveryException;
import org.sleuthkit.autopsy.discovery.search.DomainSearch;

/**
 * SwingWorker to retrieve a list of artifacts for a specified type and domain.
 */
class MiniTimelineWorker extends SwingWorker<List<MiniTimelineResult>, Void> {

    private final static Logger logger = Logger.getLogger(MiniTimelineWorker.class.getName());
    private final String domain;

    /**
     * Construct a new ArtifactsWorker.
     *
     * @param artifactType The type of artifact being retrieved.
     * @param domain       The domain the artifacts should have as an attribute.
     */
    MiniTimelineWorker(String domain) {
        this.domain = domain;
    }

    @Override
    protected List<MiniTimelineResult> doInBackground() throws Exception {
        List<MiniTimelineResult> results = new ArrayList<>();
        if (!StringUtils.isBlank(domain)) {
            DomainSearch domainSearch = new DomainSearch();
            try {
                results.addAll(domainSearch.getAllArtifactsForDomain(Case.getCurrentCase().getSleuthkitCase(), domain));

            } catch (DiscoveryException ex) {
                if (ex.getCause() instanceof InterruptedException) {
                    logger.log(Level.INFO, "MiniTimeline search was cancelled or interrupted for domain: {0}", domain);
                } else {
                    throw ex;
                }
            }
        }
        return results;
    }

    @Override
    protected void done() {
        List<MiniTimelineResult> results = new ArrayList<>();
        if (!isCancelled()) {
            try {
                results.addAll(get());
                DiscoveryEventUtils.getDiscoveryEventBus().post(new DiscoveryEventUtils.MiniTimelineResultEvent(results));
            } catch (InterruptedException | ExecutionException ex) {
                logger.log(Level.SEVERE, "Exception while trying to get list of artifacts for Domain details for mini timeline view for domain: " + domain, ex);
            } catch (CancellationException ignored) {
                //Worker was cancelled after previously finishing its background work, exception ignored to cut down on non-helpful logging
            }
        }

    }
}
