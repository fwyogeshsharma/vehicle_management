package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.BodyType;
import com.vehiclemanagement.domain.Capacity;
import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.GoodsType;
import com.vehiclemanagement.domain.State;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Every pick list in one payload, each row carrying the id the other endpoints take. */
public final class MasterDtos {

    private MasterDtos() {
    }

    public record City_(Long id, String name) {
    }

    public record StateWithCities(Long id, String code, String name, List<City_> cities) {
    }

    public record Item(Long id, String name) {
    }

    public record CapacityItem(Long id, String label, BigDecimal tons) {
    }

    public record All(List<StateWithCities> states, List<CapacityItem> capacities,
                      List<Item> bodyTypes, List<Item> goodsTypes) {

        public static All of(List<State> states, List<City> cities, List<Capacity> capacities,
                             List<BodyType> bodyTypes, List<GoodsType> goods) {
            Map<Long, List<City_>> byState = cities.stream().collect(Collectors.groupingBy(
                    City::getStateId,
                    Collectors.mapping(c -> new City_(c.getId(), c.getName()), Collectors.toList())));
            return new All(
                    states.stream().map(s -> new StateWithCities(s.getId(), s.getCode(),
                            s.getName(), byState.getOrDefault(s.getId(), List.of()))).toList(),
                    capacities.stream()
                            .map(c -> new CapacityItem(c.getId(), c.getLabel(), c.getTons())).toList(),
                    bodyTypes.stream().map(b -> new Item(b.getId(), b.getName())).toList(),
                    goods.stream().map(g -> new Item(g.getId(), g.getName())).toList());
        }
    }
}
