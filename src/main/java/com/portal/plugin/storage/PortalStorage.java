package com.portal.plugin.storage;

import com.portal.plugin.Portal;
import com.portal.plugin.PortalConnection;

import java.util.List;

/**
 * PortalStorage - Abstraction layer for portal data persistence.
 *
 * Implementations:
 * - {@link JsonPortalStorage} — stores data in a JSON file (default)
 * - {@link SQLitePortalStorage} — stores data in a local SQLite database
 *
 * The active backend is selected via {@code storage.type} in config.yml.
 */
public interface PortalStorage {

    /**
     * Initialise the storage backend (create tables, open files, etc.).
     * Called once on plugin enable.
     */
    void init();

    /**
     * Save all portals and connections.
     *
     * @param portals     list of portals to persist
     * @param connections list of connections to persist
     */
    void saveAll(List<Portal> portals, List<PortalConnection> connections);

    /**
     * Load all portals from the storage backend.
     *
     * @return list of loaded portals (may be empty, never null)
     */
    List<Portal> loadPortals();

    /**
     * Load all connections from the storage backend.
     *
     * @return list of loaded connections (may be empty, never null)
     */
    List<PortalConnection> loadConnections();

    /**
     * Close the storage backend (flush buffers, close DB connections, etc.).
     * Called once on plugin disable.
     */
    void close();
}
