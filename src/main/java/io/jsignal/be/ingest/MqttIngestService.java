package io.jsignal.be.ingest;

import io.jsignal.be.config.AppProperties;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttIngestService implements SmartLifecycle, MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestService.class);
    private static final long RETRY_DELAY_S = 5;
    private static final int QOS = 1;

    private final AppProperties.Mqtt properties;
    private final Map<String, DomainDecoder> decoders;
    private final TrackPointWriter writer;
    private final ScheduledExecutorService connectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mqtt-connect");
                t.setDaemon(true);
                return t;
            });

    private volatile MqttClient client;
    private volatile boolean running;

    public MqttIngestService(AppProperties appProperties, List<DomainDecoder> decoders,
                             TrackPointWriter writer) {
        this.properties = appProperties.mqtt();
        this.decoders = decoders.stream()
                .collect(Collectors.toUnmodifiableMap(DomainDecoder::domainPrefix, Function.identity()));
        this.writer = writer;
    }

    @Override
    public void start() {
        running = true;
        try {
            client = new MqttClient(properties.brokerUrl(),
                    "jsignal-be-" + UUID.randomUUID(), new MemoryPersistence());
            client.setCallback(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create MQTT client", e);
        }
        connectExecutor.execute(this::tryConnect);
    }

    private void tryConnect() {
        if (!running) {
            return;
        }
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        try {
            client.connect(options);
        } catch (Exception e) {
            log.warn("MQTT connect to {} failed ({}), retrying in {}s",
                    properties.brokerUrl(), e.toString(), RETRY_DELAY_S);
            connectExecutor.schedule(this::tryConnect, RETRY_DELAY_S, TimeUnit.SECONDS);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.warn("MQTT {} to {}", reconnect ? "reconnected" : "connected", serverURI);
        for (String topic : properties.topics()) {
            try {
                client.subscribe(topic, QOS);
                log.info("Subscribed to {} (QoS {})", topic, QOS);
            } catch (Exception e) {
                log.error("Failed to subscribe to {}", topic, e);
            }
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.toString());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        DomainDecoder decoder = decoders.get(domainPrefix(topic));
        if (decoder == null) {
            log.debug("No decoder for topic {}, discarded", topic);
            return;
        }
        decoder.decode(topic, message.getPayload(), Instant.now()).ifPresent(writer::enqueue);
    }

    static String domainPrefix(String topic) {
        int slash = topic.indexOf('/');
        return slash < 0 ? topic : topic.substring(0, slash);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    @Override
    public void stop() {
        running = false;
        connectExecutor.shutdownNow();
        MqttClient c = client;
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.disconnect();
                }
                c.close();
            } catch (Exception e) {
                log.warn("Error while closing MQTT client: {}", e.toString());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
