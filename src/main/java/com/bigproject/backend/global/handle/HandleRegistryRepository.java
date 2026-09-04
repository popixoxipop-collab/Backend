package com.bigproject.backend.global.handle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface HandleRegistryRepository extends JpaRepository<HandleRegistry, UUID> {
}
