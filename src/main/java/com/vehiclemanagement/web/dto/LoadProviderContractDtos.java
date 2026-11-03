package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.LoadProviderContract;
import com.vehiclemanagement.service.LoadProviderContractService;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Load-provider contract terms on the wire. Only the customer is required. */
public final class LoadProviderContractDtos {

    private LoadProviderContractDtos() {
    }

    /** The company is chosen from the customer master by id; its name is not accepted here. */
    public record SaveRequest(Long consignorId,
                              Long cityId,
                              @Size(max = 160) String location,
                              BigDecimal priceApproved,
                              BigDecimal loadingCharges,
                              BigDecimal unloadingCharges,
                              BigDecimal commissionPercentage,
                              BigDecimal munshiana,
                              BigDecimal dala,
                              BigDecimal finalPriceForTruck,
                              BigDecimal perTonnePrice,
                              @Size(max = 2000) String notes) {

        public LoadProviderContractService.Request toRequest() {
            return new LoadProviderContractService.Request(consignorId, cityId, location,
                    priceApproved, loadingCharges, unloadingCharges, commissionPercentage,
                    munshiana, dala, finalPriceForTruck, perTonnePrice, notes);
        }
    }

    public record Detail(Long id, Long consignorId, String companyName,
                         Long cityId, String location,
                         BigDecimal priceApproved, BigDecimal loadingCharges,
                         BigDecimal unloadingCharges, BigDecimal commissionPercentage,
                         BigDecimal munshiana, BigDecimal dala,
                         BigDecimal finalPriceForTruck, BigDecimal perTonnePrice,
                         String notes, boolean active, String recordedBy,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static Detail from(LoadProviderContract c) {
            return new Detail(c.getId(), c.getConsignorId(), c.getCompanyName(),
                    c.getCityId(), c.getLocation(),
                    c.getPriceApproved(), c.getLoadingCharges(), c.getUnloadingCharges(),
                    c.getCommissionPercentage(), c.getMunshiana(), c.getDala(),
                    c.getFinalPriceForTruck(), c.getPerTonnePrice(),
                    c.getNotes(), c.isActive(), c.getRecordedBy(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }
}
