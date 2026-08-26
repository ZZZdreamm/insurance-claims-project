package com.kmultan.payout.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FundReservationRepository extends JpaRepository<FundReservation, UUID> {}
