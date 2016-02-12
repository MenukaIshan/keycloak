/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.cache.infinispan.locking;

import org.infinispan.Cache;
import org.jboss.logging.Logger;
import org.keycloak.models.cache.RealmCache;
import org.keycloak.models.cache.entities.CachedClient;
import org.keycloak.models.cache.entities.CachedClientTemplate;
import org.keycloak.models.cache.entities.CachedGroup;
import org.keycloak.models.cache.entities.CachedRealm;
import org.keycloak.models.cache.entities.CachedRole;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class LockingRealmCache implements RealmCache {

    protected static final Logger logger = Logger.getLogger(LockingRealmCache.class);

    protected final Cache<String, Long> revisions;
    protected final Cache<String, Object> cache;

    protected final ConcurrentHashMap<String, String> realmLookup;

    public LockingRealmCache(Cache<String, Object> cache, Cache<String, Long> revisions, ConcurrentHashMap<String, String> realmLookup) {
        this.cache = cache;
        this.realmLookup = realmLookup;
        this.revisions = revisions;
    }

    public Cache<String, Object> getCache() {
        return cache;
    }

    public Cache<String, Long> getRevisions() {
        return revisions;
    }

    public Long getCurrentRevision(String id) {
        return revisions.get(id);
    }

    public void endRevisionBatch() {
        try {
            revisions.endBatch(true);
        } catch (Exception e) {
        }

    }

    private <T> T get(String id, Class<T> type) {
        Revisioned o = (Revisioned)cache.get(id);
        if (o == null) {
            return null;
        }
        Long rev = revisions.get(id);
        if (rev == null) {
            logger.tracev("get() missing rev");
            return null;
        }
        long oRev = o.getRevision() == null ? -1L : o.getRevision().longValue();
        if (rev > oRev) {
            logger.tracev("get() rev: {0} o.rev: {1}", rev.longValue(), oRev);
            return null;
        }
        return o != null && type.isInstance(o) ? type.cast(o) : null;
    }

    protected Object invalidateObject(String id) {
        Object removed = cache.remove(id);
        revisions.put(id, UpdateCounter.next());
        return removed;
    }

    protected void addRevisioned(String id, Revisioned object) {
        //startRevisionBatch();
        try {
            //revisions.getAdvancedCache().lock(id);
            Long rev = revisions.get(id);
            if (rev == null) {
                rev = UpdateCounter.current();
                revisions.put(id, rev);
            }
            revisions.startBatch();
            if (!revisions.getAdvancedCache().lock(id)) {
                logger.trace("Could not obtain version lock");
            }
            rev = revisions.get(id);
            if (rev == null) {
                return;
            }
            if (rev.equals(object.getRevision())) {
                cache.putForExternalRead(id, object);
                return;
            }
            if (rev > object.getRevision()) { // revision is ahead, don't cache
                return;
            }
            // revisions cache has a lower value than the object.revision, so update revision and add it to cache
            revisions.put(id, object.getRevision());
            cache.putForExternalRead(id, object);
        } finally {
            endRevisionBatch();
        }

    }




    @Override
    public void clear() {
        cache.clear();
    }

    @Override
    public CachedRealm getCachedRealm(String id) {
        return get(id, CachedRealm.class);
    }

    @Override
    public void invalidateCachedRealm(CachedRealm realm) {
        logger.tracev("Invalidating realm {0}", realm.getId());
        invalidateObject(realm.getId());
        realmLookup.remove(realm.getName());
    }

    @Override
    public void invalidateCachedRealmById(String id) {
        CachedRealm cached = (CachedRealm) invalidateObject(id);
        if (cached != null) realmLookup.remove(cached.getName());
    }

    @Override
    public void addCachedRealm(CachedRealm realm) {
        logger.tracev("Adding realm {0}", realm.getId());
        addRevisioned(realm.getId(), (Revisioned) realm);
        realmLookup.put(realm.getName(), realm.getId());
    }


    @Override
    public CachedRealm getCachedRealmByName(String name) {
        String id = realmLookup.get(name);
        return id != null ? getCachedRealm(id) : null;
    }

    @Override
    public CachedClient getApplication(String id) {
        return get(id, CachedClient.class);
    }

    @Override
    public void invalidateApplication(CachedClient app) {
        logger.tracev("Removing application {0}", app.getId());
        invalidateObject(app.getId());
    }

    @Override
    public void addCachedClient(CachedClient app) {
        logger.tracev("Adding application {0}", app.getId());
        addRevisioned(app.getId(), (Revisioned) app);
    }

    @Override
    public void invalidateCachedApplicationById(String id) {
        CachedClient client = (CachedClient)invalidateObject(id);
        if (client != null) logger.tracev("Removing application {0}", client.getClientId());
    }

    @Override
    public void evictCachedApplicationById(String id) {
        logger.tracev("Evicting application {0}", id);
        cache.evict(id);
    }

    @Override
    public CachedGroup getGroup(String id) {
        return get(id, CachedGroup.class);
    }

    @Override
    public void invalidateGroup(CachedGroup role) {
        logger.tracev("Removing group {0}", role.getId());
        invalidateObject(role.getId());
    }

    @Override
    public void addCachedGroup(CachedGroup role) {
        logger.tracev("Adding group {0}", role.getId());
        addRevisioned(role.getId(), (Revisioned) role);
    }

    @Override
    public void invalidateCachedGroupById(String id) {
        logger.tracev("Removing group {0}", id);
        invalidateObject(id);

    }

    @Override
    public void invalidateGroupById(String id) {
        logger.tracev("Removing group {0}", id);
        invalidateObject(id);
    }

    @Override
    public CachedRole getRole(String id) {
        return get(id, CachedRole.class);
    }

    @Override
    public void invalidateRole(CachedRole role) {
        logger.tracev("Removing role {0}", role.getId());
        invalidateObject(role.getId());
    }

    @Override
    public void invalidateRoleById(String id) {
        logger.tracev("Removing role {0}", id);
        invalidateObject(id);
    }

    @Override
    public void evictCachedRoleById(String id) {
        logger.tracev("Evicting role {0}", id);
        cache.evict(id);
    }

    @Override
    public void addCachedRole(CachedRole role) {
        logger.tracev("Adding role {0}", role.getId());
        addRevisioned(role.getId(), (Revisioned) role);
    }

    @Override
    public void invalidateCachedRoleById(String id) {
        logger.tracev("Removing role {0}", id);
        invalidateObject(id);
    }

    @Override
    public CachedClientTemplate getClientTemplate(String id) {
        return get(id, CachedClientTemplate.class);
    }

    @Override
    public void invalidateClientTemplate(CachedClientTemplate app) {
        logger.tracev("Removing client template {0}", app.getId());
        invalidateObject(app.getId());
    }

    @Override
    public void addCachedClientTemplate(CachedClientTemplate app) {
        logger.tracev("Adding client template {0}", app.getId());
        addRevisioned(app.getId(), (Revisioned) app);
    }

    @Override
    public void invalidateCachedClientTemplateById(String id) {
        logger.tracev("Removing client template {0}", id);
        invalidateObject(id);
    }

    @Override
    public void evictCachedClientTemplateById(String id) {
        logger.tracev("Evicting client template {0}", id);
        cache.evict(id);
    }


}
