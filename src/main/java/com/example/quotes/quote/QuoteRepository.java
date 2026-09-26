package com.example.quotes.quote;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface QuoteRepository extends JpaRepository<Quote, UUID> {
}
