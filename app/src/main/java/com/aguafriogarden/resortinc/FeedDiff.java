package com.aguafriogarden.resortinc;

import com.aguafriogarden.resortinc.network.MyStatusesResponse;
import com.aguafriogarden.resortinc.network.ReservationSummary;

import java.util.List;
import java.util.Objects;

/**
 * What one GET /booking/my-statuses poll means for the records the app already holds. Pure logic
 * (no Android, no network) so the rules the live screens depend on can be tested directly:
 * <ul>
 *   <li>a record whose status differs is patched in place ({@link #displayChanged}) — its badge
 *       re-binds without a reload;</li>
 *   <li>a different set of records (a request was paid and became a booking, a record appeared or
 *       vanished) or a status that moved to another kind/key means the full list must be
 *       refetched ({@link #needsRefetch}), so the other fields follow;</li>
 *   <li>a status merely re-worded or re-coloured under the same kind/key only needs the in-place
 *       patch.</li>
 * </ul>
 */
final class FeedDiff {

    /** At least one record's status was patched in {@code rows}. */
    final boolean displayChanged;
    /** The records or a status moved: refetch GET /booking/my-bookings. */
    final boolean needsRefetch;

    private FeedDiff(boolean displayChanged, boolean needsRefetch) {
        this.displayChanged = displayChanged;
        this.needsRefetch = needsRefetch;
    }

    /** Patches {@code rows}' statuses from {@code feed} in place and reports what it found. */
    static FeedDiff apply(List<ReservationSummary> rows, List<MyStatusesResponse.Row> feed) {
        boolean sameSet = feed.size() == rows.size();
        boolean displayChanged = false;
        boolean statusMoved = false;
        for (MyStatusesResponse.Row incoming : feed) {
            ReservationSummary mine = find(rows, incoming.kind, incoming.id);
            if (mine == null) {
                sameSet = false;
                continue;
            }
            if (incoming.status == null || incoming.status.equals(mine.status)) {
                continue;
            }
            if (mine.status == null || !mine.status.token().equals(incoming.status.token())) {
                statusMoved = true;
            }
            mine.status = incoming.status;
            displayChanged = true;
        }
        return new FeedDiff(displayChanged, !sameSet || statusMoved);
    }

    static ReservationSummary find(List<ReservationSummary> rows, String kind, int id) {
        for (ReservationSummary row : rows) {
            if (row.id == id && Objects.equals(row.kind, kind)) {
                return row;
            }
        }
        return null;
    }
}
