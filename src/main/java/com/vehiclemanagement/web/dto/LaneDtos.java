package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.FreightLane;
import com.vehiclemanagement.service.LaneService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * Lane intelligence on the wire.
 *
 * <p>Only three fields are required, and that is the design: this is written down mid-call
 * about something else. A form demanding a tonnage gets an invented tonnage.
 */
public final class LaneDtos {

    private LaneDtos() {
    }

    public record SaveRequest(Long fromCityId,
                              @NotBlank(message = "Where does it start?")
                              @Size(max = 120) String fromPlace,
                              Long toCityId,
                              @NotBlank(message = "Where does it go?")
                              @Size(max = 120) String toPlace,
                              Long goodsTypeId,
                              @Size(max = 160) String goods,
                              @Size(max = 160) String companyName,
                              Boolean seasonal,
                              @Size(max = 80) String season,
                              Long bodyTypeId,
                              @Size(max = 160) String source,
                              String sourceMobile,
                              @Size(max = 2000) String notes) {

        public LaneService.Request toRequest() {
            return new LaneService.Request(fromCityId, fromPlace, toCityId, toPlace,
                    goodsTypeId, goods, companyName, seasonal, season, bodyTypeId,
                    source, sourceMobile, notes);
        }
    }

    public record Detail(Long id,
                         Long fromCityId, String fromPlace,
                         Long toCityId, String toPlace,
                         Long goodsTypeId, String goods,
                         String companyName, boolean seasonal, String season, Long bodyTypeId,
                         String source, String sourceMobile, String notes,
                         boolean active, String recordedBy,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static Detail from(FreightLane l) {
            return new Detail(l.getId(),
                    l.getFromCityId(), l.getFromPlace(),
                    l.getToCityId(), l.getToPlace(),
                    l.getGoodsTypeId(), l.getGoods(),
                    l.getCompanyName(), l.isSeasonal(), l.getSeason(), l.getBodyTypeId(),
                    l.getSource(), l.getSourceMobile(), l.getNotes(),
                    l.isActive(), l.getRecordedBy(), l.getCreatedAt(), l.getUpdatedAt());
        }
    }

}
