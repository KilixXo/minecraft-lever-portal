package com.portal.plugin;

/**
 * PortalConnection - Represents a bidirectional connection between two portals.
 */
public class PortalConnection {

    private final String portal1Id;
    private final String portal2Id;

    public PortalConnection(String portal1Id, String portal2Id) {
        this.portal1Id = portal1Id;
        this.portal2Id = portal2Id;
    }

    public String getPortal1Id() {
        return this.portal1Id;
    }

    public String getPortal2Id() {
        return this.portal2Id;
    }

    /**
     * Check if this connection involves the given portal ID.
     */
    public boolean involves(String portalId) {
        return portal1Id.equals(portalId) || portal2Id.equals(portalId);
    }

    /**
     * Get the other portal ID in this connection.
     */
    public String getOtherPortal(String portalId) {
        if (portal1Id.equals(portalId)) {
            return portal2Id;
        } else if (portal2Id.equals(portalId)) {
            return portal1Id;
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortalConnection)) return false;
        PortalConnection that = (PortalConnection) o;
        // Connection is bidirectional, so check both ways
        return (this.portal1Id.equals(that.portal1Id) && this.portal2Id.equals(that.portal2Id)) ||
               (this.portal1Id.equals(that.portal2Id) && this.portal2Id.equals(that.portal1Id));
    }

    @Override
    public int hashCode() {
        // FIX-20: use sum of individual hashes instead of XOR to reduce collisions.
        // XOR of two equal strings is always 0; XOR of symmetric pairs (a,b) and (b,a)
        // is identical by design (bidirectional), but XOR also produces identical results
        // for many unrelated pairs. Summing the hashes preserves commutativity while
        // distributing values more evenly across the hash space.
        return portal1Id.hashCode() + portal2Id.hashCode();
    }

    @Override
    public String toString() {
        return "PortalConnection{" + portal1Id + " <-> " + portal2Id + "}";
    }
}
