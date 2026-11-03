package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.LoadRequest;
import com.vehiclemanagement.service.LoadRequestService;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Load requests on the wire. The customer is chosen by id from the master; its name is not
 * accepted here. Everything past the customer and the route is optional.
 */
public final class LoadRequestDtos {

    private LoadRequestDtos() {
    }

    public record SaveRequest(Long consignorId,
                              LocalDate requestDate,
                              Long fromCityId,
                              @Size(max = 120) String fromPlace,
                              Long toCityId,
                              @Size(max = 120) String toPlace,
                              Long goodsTypeId,
                              @Size(max = 160) String goods,
                              BigDecimal weightTons,
                              Integer trucksRequired,
                              Long bodyTypeId,
                              LocalDate loadingDate,
                              BigDecimal offeredPrice,
                              @Size(max = 120) String contactName,
                              String contactMobile,
                              @Size(max = 2000) String notes,
                              @Size(max = 16) String status) {

        public LoadRequestService.Request toRequest() {
            return new LoadRequestService.Request(consignorId, requestDate, fromCityId, fromPlace,
                    toCityId, toPlace, goodsTypeId, goods, weightTons, trucksRequired, bodyTypeId,
                    loadingDate, offeredPrice, contactName, contactMobile, notes, status);
        }
    }

    /** The vehicles that run where a request wants to go, and which of its places could be matched. */
    public record VehicleMatch(String match, boolean fromMatchable, boolean toMatchable,
                               java.util.List<VehicleDtos.Summary> vehicles) {
    }

    public record Detail(Long id, Long consignorId, String companyName, LocalDate requestDate,
                         Long fromCityId, String fromPlace, Long toCityId, String toPlace,
                         Long goodsTypeId, String goods,
                         BigDecimal weightTons, int trucksRequired, Long bodyTypeId,
                         LocalDate loadingDate, BigDecimal offeredPrice,
                         String contactName, String contactMobile, String notes,
                         String status, String recordedBy,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static Detail from(LoadRequest r) {
            return new Detail(r.getId(), r.getConsignorId(), r.getCompanyName(),
                    r.getRequestDate(), r.getFromCityId(), r.getFromPlace(), r.getToCityId(),
                    r.getToPlace(), r.getGoodsTypeId(), r.getGoods(), r.getWeightTons(),
                    r.getTrucksRequired(), r.getBodyTypeId(), r.getLoadingDate(),
                    r.getOfferedPrice(), r.getContactName(), r.getContactMobile(), r.getNotes(),
                    r.getStatus().name(), r.getRecordedBy(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }
}
