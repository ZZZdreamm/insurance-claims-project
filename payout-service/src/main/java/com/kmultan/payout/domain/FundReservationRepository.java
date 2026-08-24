package com.kmultan.payout.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FundReservationRepository extends JpaRepository<FundReservation, UUID> {}
