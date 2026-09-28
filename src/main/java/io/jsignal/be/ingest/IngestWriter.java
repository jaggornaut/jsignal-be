package io.jsignal.be.ingest;

import io.jsignal.be.config.AppProperties;
import io.jsignal.be.entity.Contact;
import io.jsignal.be.entity.Receiver;
import io.jsignal.be.ingest.ContactSnapshot.Key;
import io.jsignal.be.repository.ContactRepository;
import io.jsignal.be.repository.PositionRepository;
import io.jsignal.be.repository.PositionRepository.NewPosition;
import io.jsignal.be.repository.ReceiverRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IngestWriter {

    private static final Logger log = LoggerFactory.getLogger(IngestWriter.class);

    private static final int MAX_BATCH_SIZE = 500;
    private static final int QUEUE_CAPACITY = 100_000;
    private static final String REPORTED = "reported";

    private final ContactRepository contacts;
    private final ReceiverRepository receivers;
    private final PositionRepository positions;
    private final TransactionTemplate transactions;
    private final String receiverName;

    private final BlockingQueue<ContactSnapshot> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<Key, Long> contactIds = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();

    private volatile Short receiverId;

    public IngestWriter(ContactRepository contacts, ReceiverRepository receivers,
                        PositionRepository positions, TransactionTemplate transactions,
                        AppProperties properties) {
        this.contacts = contacts;
        this.receivers = receivers;
        this.positions = positions;
        this.transactions = transactions;
        this.receiverName = properties.receiverName();
    }

    public void enqueue(ContactSnapshot snapshot) {
        if (!queue.offer(snapshot)) {
            long count = dropped.incrementAndGet();
            log.warn("Write queue full, dropped snapshot (total dropped: {})", count);
        }
    }

    @Scheduled(fixedDelay = 500)
    public void flush() {
        List<ContactSnapshot> batch = new ArrayList<>(MAX_BATCH_SIZE);
        queue.drainTo(batch, MAX_BATCH_SIZE);
        write(batch);
    }

    @PreDestroy
    public void flushAll() {
        List<ContactSnapshot> rest = new ArrayList<>();
        queue.drainTo(rest);
        write(rest);
        if (!rest.isEmpty()) {
            log.info("Flushed {} remaining snapshots on shutdown", rest.size());
        }
    }

    private void write(List<ContactSnapshot> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> persist(batch));
        } catch (Exception e) {
            contactIds.clear();
            receiverId = null;
            log.error("Failed to write batch of {} snapshots", batch.size(), e);
        }
    }

    private void persist(List<ContactSnapshot> batch) {
        Map<Key, Observed> observed = new LinkedHashMap<>();
        for (ContactSnapshot snapshot : batch) {
            observed.computeIfAbsent(snapshot.key(), k -> new Observed()).merge(snapshot);
        }
        observed.forEach(this::upsertContact);

        List<NewPosition> rows = new ArrayList<>(batch.size());
        for (ContactSnapshot snapshot : batch) {
            if (!snapshot.hasPosition()) {
                continue;
            }
            rows.add(new NewPosition(snapshot.ts(), contactIds.get(snapshot.key()),
                    snapshot.domain(), resolveReceiverId(), REPORTED,
                    snapshot.lat(), snapshot.lon(), snapshot.altM(), snapshot.speedMps(),
                    snapshot.courseDeg(), snapshot.headingDeg(), snapshot.rawJson()));
        }
        if (!rows.isEmpty()) {
            positions.insertAll(rows);
        }
    }

    private void upsertContact(Key key, Observed seen) {
        Long id = contactIds.get(key);
        Contact contact = id == null
                ? contacts.findByDomainAndIdentifier(key.domain(), key.identifier()).orElse(null)
                : contacts.findById(id).orElse(null);
        if (contact == null) {
            contact = contacts.save(new Contact(key.domain(), key.identifier(), seen.name,
                    seen.firstSeen, seen.lastSeen, seen.details));
        } else {
            contact.observed(seen.name, seen.details, seen.firstSeen, seen.lastSeen);
        }
        contactIds.put(key, contact.getId());
    }

    private Short resolveReceiverId() {
        Short cached = receiverId;
        if (cached != null) {
            return cached;
        }
        Short resolved = receivers.findByName(receiverName)
                .orElseGet(() -> receivers.save(new Receiver(receiverName)))
                .getId();
        receiverId = resolved;
        return resolved;
    }

    private static final class Observed {

        private String name;
        private String details;
        private Instant firstSeen;
        private Instant lastSeen;

        void merge(ContactSnapshot snapshot) {
            if (firstSeen == null || snapshot.ts().isBefore(firstSeen)) {
                firstSeen = snapshot.ts();
            }
            if (lastSeen == null || !snapshot.ts().isBefore(lastSeen)) {
                lastSeen = snapshot.ts();
                if (snapshot.name() != null) {
                    name = snapshot.name();
                }
                if (snapshot.rawJson() != null) {
                    details = snapshot.rawJson();
                }
            }
        }
    }
}
