package com.vehiclemanagement.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * One page of results.
 *
 * <p>Deliberately not Spring Data's {@code Page}, whose JSON carries {@code pageable},
 * {@code sort}, {@code first}, {@code last}, {@code numberOfElements} and a nested
 * {@code Pageable} — a shape that leaks the server's persistence library into the client's
 * contract and changes when that library does.
 *
 * <p>{@code page} is 1-based here, matching what the client sent.
 */
public record PageResponse<T>(List<T> items, long total, int page, int pageSize) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> toDto) {
        return new PageResponse<>(
                page.getContent().stream().map(toDto).toList(),
                page.getTotalElements(),
                page.getNumber() + 1,
                page.getSize());
    }
}
