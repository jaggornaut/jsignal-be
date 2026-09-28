package io.jsignal.be.service;

import io.jsignal.be.config.AppProperties;
import io.jsignal.be.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty("app.retention-days")
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final PositionRepository repository;
    private final int retentionDays;

    public RetentionService(PositionRepository repository, AppProperties properties) {
        Integer days = properties.retentionDays();
        if (days == null || days <= 0) {
            throw new IllegalArgumentException("app.retention-days must be a positive integer");
        }
        this.repository = repository;
        this.retentionDays = days;
    }

    @Scheduled(cron = "${app.retention-cron:0 30 3 * * *}")
    @Transactional
    public void purgeOldChunks() {
        long chunks = repository.dropChunksOlderThan(retentionDays);
        log.info("Retention job dropped {} chunks older than {} days", chunks, retentionDays);
    }
}
