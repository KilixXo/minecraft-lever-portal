package com.portal.plugin.storage;

import com.portal.plugin.Portal;
import com.portal.plugin.PortalConnection;

import java.util.List;

/**
 * PortalStorage — abstraction layer for portal data persistence.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link JsonPortalStorage}       — JSON file (default, no extra setup)</li>
 *   <li>{@link SQLitePortalStorage}     — local SQLite database file</li>
 *   <li>{@link MySQLPortalStorage}      — remote MySQL or MariaDB server</li>
 *   <li>{@link PostgreSQLPortalStorage} — remote PostgreSQL server</li>
 * </ul>
 *
 * <p>The active backend is selected via {@code storage.type} in {@code config.yml}.
 * Connection parameters (host, port, username, password, database name) are read
 * from the {@code storage.database} section by {@link DatabaseConfig}.
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
