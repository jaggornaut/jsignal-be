package io.jsignal.be.ingest;

import io.jsignal.be.entity.TrackPoint;
import io.jsignal.be.repository.TrackPointRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TrackPointWriter {

    private static final Logger log = LoggerFactory.getLogger(TrackPointWriter.class);

    private static final int MAX_BATCH_SIZE = 500;
    private static final int QUEUE_CAPACITY = 100_000;

    private final TrackPointRepository repository;
    private final BlockingQueue<TrackPoint> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    public TrackPointWriter(TrackPointRepository repository) {
        this.repository = repository;
    }

    public void enqueue(TrackPoint point) {
        if (!queue.offer(point)) {
            long count = dropped.incrementAndGet();
            log.warn("Write queue full, dropped track point (total dropped: {})", count);
        }
    }

    @Scheduled(fixedDelay = 500)
    public void flush() {
        List<TrackPoint> batch = new ArrayList<>(MAX_BATCH_SIZE);
        queue.drainTo(batch, MAX_BATCH_SIZE);
        insert(batch);
    }

    @PreDestroy
    public void flushAll() {
        List<TrackPoint> rest = new ArrayList<>();
        queue.drainTo(rest);
        insert(rest);
        if (!rest.isEmpty()) {
            log.info("Flushed {} remaining track points on shutdown", rest.size());
        }
    }

    private void insert(List<TrackPoint> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            repository.saveAll(batch);
        } catch (Exception e) {
            log.error("Failed to insert batch of {} track points", batch.size(), e);
        }
    }
}
