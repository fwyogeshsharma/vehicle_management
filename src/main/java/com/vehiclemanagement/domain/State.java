package com.vehiclemanagement.domain;

import jakarta.persistence.*;

/** An Indian state or union territory. Seeded from india_states_cities.txt. */
@Entity
@Table(name = "states")
public class State {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 3, unique = true)
    private String code;

    @Column(nullable = false, length = 80, unique = true)
    private String name;

    protected State() {
    }

    public State(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
