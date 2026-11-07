package com.gs5.kycaml.risk.adapters.projection;

import java.util.UUID;

/**
 * One state to project onto the query side: the key of the view, the profile version it is as of,
 * and the compliance view as JSON. Confidential (BR-43): never printed.
 */
public record Projection(UUID tenantId, String customerNumber, long version, String viewJson) {

    @Override
    public String toString() {
        return "Projection[***]";
    }
}
