package com.example.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** A scheduled job that runs every 20 seconds and fails on every third run. */
@Component
public class NightlyReport {

    private static final Logger log = LoggerFactory.getLogger(NightlyReport.class);

    private final AtomicInteger runs = new AtomicInteger();

    @Scheduled(initialDelay = 10_000, fixedDelay = 20_000)
    public void build() {
        int run = runs.incrementAndGet();
        log.info("Building report, run {}", run);
        log.debug("Reading 1,240 orders from the last 24 hours");
        log.debug("Grouping by region: 4 regions");
        if (run % 3 == 0) {
            throw new IllegalStateException("Region 'EU' has no exchange rate for today");
        }
        log.info("Report run {} done", run);
    }
}
