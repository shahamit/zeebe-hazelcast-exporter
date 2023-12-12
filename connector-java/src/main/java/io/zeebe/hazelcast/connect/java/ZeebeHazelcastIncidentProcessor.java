package io.zeebe.hazelcast.connect.java;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hazelcast.client.HazelcastClientNotActiveException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.ReadResultSet;
import com.hazelcast.ringbuffer.Ringbuffer;
import io.zeebe.exporter.proto.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class ZeebeHazelcastIncidentProcessor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZeebeHazelcastIncidentProcessor.class);

    private static final List<Class<? extends com.google.protobuf.Message>> RECORD_MESSAGE_TYPES;

    static {
        RECORD_MESSAGE_TYPES = new ArrayList<>();
        RECORD_MESSAGE_TYPES.add(Schema.IncidentRecord.class);
        RECORD_MESSAGE_TYPES.add(Schema.VariableRecord.class);
    }

    private final Ringbuffer<byte[]> incidentRingBuffer;
    private final Map<Class<?>, List<Consumer<?>>> listeners;
    private final Consumer<Long> postProcessListener;

    private long sequence;

    private Future<?> future;
    private ExecutorService executorService;

    private volatile boolean isClosed = false;

    private ZeebeHazelcastIncidentProcessor(Ringbuffer<byte[]> incidentRingBuffer, long sequence,
                                            Map<Class<?>, List<Consumer<?>>> listeners, Consumer<Long> postProcessListener) {
        this.incidentRingBuffer = incidentRingBuffer;
        this.sequence = sequence;
        this.listeners = listeners;
        this.postProcessListener = postProcessListener;
    }

    /**
     * Returns a new builder to read from the ringbuffer.
     */
    public static Builder newBuilder(HazelcastInstance hazelcastInstance) {
        return new ZeebeHazelcastIncidentProcessor.Builder(hazelcastInstance);
    }

    private void start() {
        executorService = Executors.newSingleThreadExecutor();
        future = executorService.submit(this::readFromBuffer);
    }

    public boolean isClosed() {
        return isClosed;
    }

    /**
     * Stop reading from the ringbuffer.
     */
    @Override
    public void close() throws Exception {
        LOGGER.info("Closing. Stop reading from ringbuffer. Current sequence: '{}'", getSequence());

        isClosed = true;

        if (future != null) {
            future.cancel(true);
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    /**
     * Returns the current sequence.
     */
    public long getSequence() {
        return sequence;
    }

    private void readFromBuffer() {
        while (!isClosed) {
            readNext();
        }
    }

    private void readNext() {
        readMany();
    }

    private void readOne() {
        try {
            readOneRecordFromHZ();
        } catch (InvalidProtocolBufferException e) {
            LOGGER.error("Failed to deserialize Protobuf message at sequence '{}'", sequence, e);
            sequence += 1;
        } catch (IllegalArgumentException e) {
            // if sequence is smaller than 0 or larger than tailSequence()+1
            final var headSequence = incidentRingBuffer.headSequence();
            LOGGER.warn(
                    "Fail to read from ring-buffer at sequence '{}'. Continue with head sequence at '{}'",
                    sequence,
                    headSequence,
                    e);

            sequence = headSequence;

        } catch (HazelcastClientNotActiveException e) {
            LOGGER.warn("Lost connection to the Hazelcast server", e);

            try {
                close();
            } catch (Exception closingFailure) {
                LOGGER.debug("Failure while closing the client", closingFailure);
            }

        } catch (Exception e) {
            if (!isClosed) {
                LOGGER.error(
                        "Fail to read from ring-buffer at sequence '{}'. Will try again.", sequence, e);
            }
        }
    }

    private void readOneRecordFromHZ() throws InterruptedException, InvalidProtocolBufferException {
        final byte[] item = incidentRingBuffer.readOne(sequence);
        final var genericRecord = Schema.Record.parseFrom(item);
        handleRecord(genericRecord);
        sequence += 1;
        postProcessListener.accept(sequence);
        if (sequence % 50 == 0) {
            LOGGER.info("Read {} records from ring buffer", sequence);
        }
    }

    private void readMany() {
        int maxCount = 500;
        LOGGER.debug("Attempting to read {} records from sequence : {}", maxCount, sequence);
        ReadResultSet<byte[]> result = incidentRingBuffer.readManyAsync(sequence, 1, maxCount, null)
                .toCompletableFuture().join();
        LOGGER.info("Read {} records from incident ring buffer", result.size());
        for (byte[] item : result) {
            processRecord(item, result.size());
        }
        sequence += result.size();
        postProcessListener.accept(sequence);
    }

    private void processRecord(byte[] item, int totalRecords) {
        try {
            final Schema.Record genericRecord = Schema.Record.parseFrom(item);
            handleRecord(genericRecord);
        } catch (InvalidProtocolBufferException e) {
            LOGGER.error("Failed to deserialize Protobuf message at sequence '{}'", sequence, e);
            sequence += totalRecords;
        } catch (IllegalArgumentException e) {
            // if sequence is smaller than 0 or larger than tailSequence()+1
            final var headSequence = incidentRingBuffer.headSequence();
            LOGGER.warn(
                    "Fail to read from ring-buffer at sequence '{}'. Continue with head sequence at '{}'",
                    sequence,
                    headSequence,
                    e);

            sequence = headSequence;

        } catch (HazelcastClientNotActiveException e) {
            LOGGER.warn("Lost connection to the Hazelcast server", e);

            try {
                close();
            } catch (Exception closingFailure) {
                LOGGER.debug("Failure while closing the client", closingFailure);
            }

        } catch (Exception e) {
            if (!isClosed) {
                LOGGER.error(
                        "Fail to read from ring-buffer at sequence '{}'. Will try again.", sequence, e);
            }
        }

    }

    private void handleRecord(Schema.Record genericRecord) throws InvalidProtocolBufferException {
        for (Class<? extends com.google.protobuf.Message> type : RECORD_MESSAGE_TYPES) {
            final var handled = handleRecord(genericRecord, type);
            if (handled) {
                return;
            }
        }
    }

    private <T extends com.google.protobuf.Message> boolean handleRecord(
            Schema.Record genericRecord, Class<T> t) throws InvalidProtocolBufferException {

        if (genericRecord.getRecord().is(t)) {
            final var record = genericRecord.getRecord().unpack(t);

            listeners
                    .getOrDefault(t, List.of())
                    .forEach(listener -> ((Consumer<T>) listener).accept(record));

            return true;
        } else {
            return false;
        }
    }

    public static class Builder {

        private final HazelcastInstance hazelcastInstance;

        private final Map<Class<?>, List<Consumer<?>>> listeners = new HashMap<>();

        private String name = "zeebe-incidents";

        private long readFromSequence = -1;
        private boolean readFromHead = false;

        private Consumer<Long> postProcessListener = sequence -> {
        };

        private Builder(HazelcastInstance hazelcastInstance) {
            this.hazelcastInstance = hazelcastInstance;
        }

        /**
         * Set the name of the ringbuffer to read from.
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Start reading from the given sequence.
         */
        public Builder readFrom(long sequence) {
            this.readFromSequence = sequence;
            readFromHead = false;
            return this;
        }

        /**
         * Start reading from the oldest item of the ringbuffer.
         */
        public Builder readFromHead() {
            readFromSequence = -1;
            readFromHead = true;
            return this;
        }


        /**
         * Register a listener that is called when an item is read from the ringbuffer and consumed by
         * the registered listeners. The listener is called with the next sequence number of the
         * ringbuffer. It can be used to store the sequence number externally.
         */
        public Builder postProcessListener(Consumer<Long> listener) {
            postProcessListener = listener;
            return this;
        }

        private <T extends com.google.protobuf.Message> void addListener(
                Class<T> recordType, Consumer<T> listener) {
            final var recordListeners = listeners.getOrDefault(recordType, new ArrayList<>());
            recordListeners.add(listener);
            listeners.put(recordType, recordListeners);
        }

        public Builder addIncidentListener(Consumer<Schema.IncidentRecord> listener) {
            addListener(Schema.IncidentRecord.class, listener);
            return this;
        }

        public Builder addVariableListener(Consumer<Schema.VariableRecord> listener) {
            addListener(Schema.VariableRecord.class, listener);
            return this;
        }

        private long getSequence(Ringbuffer<?> ringbuffer) {

            final var headSequence = ringbuffer.headSequence();
            final var tailSequence = ringbuffer.tailSequence();

            if (readFromSequence > 0) {
                if (readFromSequence > (tailSequence + 1)) {
                    LOGGER.info(
                            "The given sequence '{}' is greater than the current tail-sequence '{}' of the ringbuffer. Using the head-sequence instead.",
                            readFromSequence,
                            tailSequence);
                    return headSequence;
                } else {
                    return readFromSequence;
                }

            } else if (readFromHead) {
                return headSequence;

            } else {
                return Math.max(headSequence, tailSequence);
            }
        }

        /**
         * Start a background task that reads from the ringbuffer and invokes the listeners. After an
         * item is read and the listeners are invoked, the sequence is incremented (at-least-once
         * semantic). <br>
         * The current sequence is returned by {@link #getSequence()}. <br>
         * Call {@link #close()} to stop reading.
         */
        public ZeebeHazelcastIncidentProcessor build() {

            LOGGER.debug("Read from incidentRingBuffer with name '{}'", name);
            final Ringbuffer<byte[]> incidentRingBuffer = hazelcastInstance.getRingbuffer(name);

            if (incidentRingBuffer == null) {
                throw new IllegalArgumentException(
                        String.format("No ring buffer found with name '%s'", name));
            }

            LOGGER.debug(
                    "Ringbuffer status: [head: {}, tail: {}, size: {}, capacity: {}]",
                    incidentRingBuffer.headSequence(),
                    incidentRingBuffer.tailSequence(),
                    incidentRingBuffer.size(),
                    incidentRingBuffer.capacity());

            final long sequence = getSequence(incidentRingBuffer);
            LOGGER.info("Read from incidentRingBuffer '{}' starting from sequence '{}'", name, sequence);

            final var zeebeHazelcast =
                    new ZeebeHazelcastIncidentProcessor(incidentRingBuffer, sequence, listeners, postProcessListener);
            zeebeHazelcast.start();

            return zeebeHazelcast;
        }
    }
}
