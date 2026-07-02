package com.blocklogs.core.query;

import com.blocklogs.api.log.LogEntry;

import java.util.List;

/**
 * One page of a flat ({@code /bl flat}) lookup.
 *
 * @param entries    the rows on this page
 * @param page       zero-based page index
 * @param pageSize   rows per page
 * @param totalCount total rows matching the query across all pages
 */
public record LookupResult(List<LogEntry> entries, int page, int pageSize, long totalCount) {

    public int totalPages() {
        if (pageSize <= 0) {
            return 1;
        }
        return (int) Math.max(1, (totalCount + pageSize - 1) / pageSize);
    }

    public boolean hasNext() {
        return (long) (page + 1) * pageSize < totalCount;
    }
}
