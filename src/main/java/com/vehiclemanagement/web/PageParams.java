package com.vehiclemanagement.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Turns the client's 1-based {@code page} / {@code page_size} into a {@link Pageable}.
 *
 * <p><b>One place, not one per controller.</b> Clamping duplicated across controllers is how the
 * cap ends up being 100 on one endpoint and 500 on another, and how a
 * {@code page=0} that means "first page" on one means "second page" on the next.
 *
 * <p>The API is 1-based because that is what a person reading a URL expects; Spring Data is
 * 0-based. The subtraction happens here and nowhere else.
 */
public final class PageParams {

    /**
     * The largest page anyone may ask for.
     *
     * <p>Not a guess: the point is that one request cannot ask the database to materialise the
     * whole fleet. A client wanting everything pages through it.
     */
    public static final int MAX_PAGE_SIZE = 200;

    public static final int DEFAULT_PAGE_SIZE = 50;

    private PageParams() {
    }

    public static Pageable of(Integer page, Integer pageSize, Sort sort) {
        int p = page == null ? 1 : page;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (p < 1) {
            throw new com.vehiclemanagement.exception.FieldValidationException(
                    "page", "Pages start at 1.");
        }
        if (size < 1) {
            throw new com.vehiclemanagement.exception.FieldValidationException(
                    "page_size", "Ask for at least one row.");
        }
        // Clamped rather than rejected: asking for too much is a reasonable thing for a client to
        // do, and silently giving it less is the conventional answer. `total` tells it the rest.
        size = Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(p - 1, size, sort);
    }
}
