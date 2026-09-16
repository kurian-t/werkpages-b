package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import org.werkpages.repository.CompanyLocationRepository;

/**
 * Turns what a form claimed into what may be stored.
 *
 * <p>Two checks that cannot live in {@link DeclaredLocation} itself, because both need the
 * database: that a selected workplace exists, and that it belongs to the company the contribution
 * is about. The second is the one that matters — without it, a request could attach a Walmart
 * address to a review of somebody at Loblaws, and the company page would then show a location
 * nobody there has ever worked at.
 */
public final class DeclaredLocationResolver {

    private final CompanyLocationRepository locationRepo;

    public DeclaredLocationResolver(CompanyLocationRepository locationRepo) {
        this.locationRepo = locationRepo;
    }

    /**
     * Validates a declaration and, at {@code exact} precision, replaces its coarse values with the
     * selected location's own.
     *
     * @param companyId the company this contribution is about; a location outside it is rejected
     * @return the declaration as it should be stored, or a failed future carrying a bad-request
     */
    public Future<DeclaredLocation> resolve(SqlClient conn, DeclaredLocation declared, Long companyId) {
        if (declared == null || declared.isEmpty()) return Future.succeededFuture(DeclaredLocation.NONE);

        String problem = declared.validate();
        if (problem != null) return Future.failedFuture(ServiceException.badRequest(problem));

        if (!DeclaredLocation.EXACT.equals(declared.precision())) {
            return Future.succeededFuture(declared);
        }

        return locationRepo.findActiveById(conn, declared.companyLocationId())
            .compose(found -> {
                if (found.isEmpty()) {
                    return Future.failedFuture(ServiceException.badRequest("That workplace no longer exists"));
                }
                var row = found.get();
                // A location belongs to exactly one company. Attaching one to a contribution about
                // a different company is always a mistake - a stale form, or a crafted request -
                // and silently accepting it would corrupt that company's facets permanently.
                if (companyId != null && !companyId.equals(row.getLong("company_id"))) {
                    return Future.failedFuture(
                        ServiceException.badRequest("That workplace belongs to a different company"));
                }
                return Future.succeededFuture(declared.withCoarseFrom(
                    row.getString("country"), row.getString("state"), row.getString("city")));
            });
    }
}
