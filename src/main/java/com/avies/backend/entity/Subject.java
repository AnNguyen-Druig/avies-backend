package com.avies.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "subjects", schema = "aives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Subject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "code", nullable = false, unique = true, length = 20)
    String code;

    @Column(name = "name", nullable = false, length = 255)
    String name;

    @Column(name = "description", columnDefinition = "TEXT")
    String description;
}