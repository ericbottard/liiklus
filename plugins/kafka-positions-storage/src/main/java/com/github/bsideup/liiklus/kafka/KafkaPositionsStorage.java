package com.github.bsideup.liiklus.kafka;

import com.github.bsideup.liiklus.positions.GroupId;
import com.github.bsideup.liiklus.positions.PositionsStorage;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteBufferDeserializer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@FieldDefaults(makeFinal = true)
@Slf4j
public class KafkaPositionsStorage implements PositionsStorage {

    private String bootstrapServers;

    private Map<GroupId, Consumer<?, ?>> consumers = new ConcurrentHashMap<>();
    private final AdminClient adminClient;

    public KafkaPositionsStorage(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);



        Map<String, Object> acProps = new HashMap<>();
        acProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        this.adminClient = AdminClient.create(acProps);
    }

    @Override
    public CompletionStage<Void> update(String topic, GroupId groupId, int partition, long position) {
        System.out.format("update(%s, %s, %d, %d)%n", topic, groupId, partition, position);
        var c = consumerFor(groupId);

        Map<TopicPartition, OffsetAndMetadata> metadata =
                Collections.singletonMap(new TopicPartition(topic, partition), new OffsetAndMetadata(position));

        return Mono.<Void>fromRunnable(() -> c.commitSync(metadata))
                .toFuture();
    }

    private Consumer<?, ?> consumerFor(GroupId groupId) {
        return consumers.computeIfAbsent(groupId, g -> {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId.asString());
            return new KafkaConsumer<>(props, new ByteBufferDeserializer(), new ByteBufferDeserializer());
        });
    }

    @Override
    public Publisher<Positions> findAll() {
        System.out.println("findAll()");

        return Mono.fromFuture(makeCompletableFuture(adminClient.listConsumerGroups().all()))
                .flatMapMany(Flux::fromIterable)
                .doOnNext(g -> System.out.format("Got [All]%s%n", g))
                .flatMap(
                        g -> {
                            GroupId groupId = GroupId.ofString(g.groupId());
                            Consumer<?, ?> consumer = consumerFor(groupId);
                            return Flux.fromIterable(consumer.listTopics().values())
                                    .flatMap(pis -> Flux.fromIterable(pis).map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                                            .collect(Collectors.toList())
                                            .map(topicPartitions -> fetchEndOffsets(topicPartitions, consumer)))
                                    .flatMapIterable(m -> m.entrySet())
                                    .groupBy(e -> e.getKey().topic())
                                    .map(byTopic -> new Positions(
                                            byTopic.key(),
                                            groupId,
                                            byTopic.collectMap(e -> e.getKey().partition(), e -> e.getValue()).block()
                                    ));
                        }
                )
                ;
    }

    @Override
    public CompletionStage<Map<Integer, Long>> findAll(String topic, GroupId groupId) {
        System.out.printf("findAll(%s, %s)%n", topic, groupId);
        List<TopicPartition> tps = fetchPartitions(topic, consumerFor(groupId)).stream()
                .map(pi -> new TopicPartition(pi.topic(), pi.partition())).collect(Collectors.toList());
        return Flux.fromIterable(fetchEndOffsets(tps, consumerFor(groupId)).entrySet())
                .collectMap(e -> e.getKey().partition(), e -> e.getValue())
                .toFuture();
    }

    @Override
    public CompletionStage<Map<Integer, Map<Integer, Long>>> findAllVersionsByGroup(String topic, String groupName) {
        System.out.printf("findAllVersionsByGroup(%s, %s)%n", topic, groupName);
        return Mono.fromFuture(makeCompletableFuture(adminClient.listConsumerGroups().all()))
                .flatMapMany(Flux::fromIterable)
                .doOnNext(g -> System.out.format("Got [g=%s]%s%n",groupName, g))
                .filter(g -> groupName.equals( GroupId.ofString(g.groupId()).getName()))
                .map(
                        g -> {
                            GroupId groupId = GroupId.ofString(g.groupId());
                            Consumer<?, ?> consumer = consumerFor(groupId);
                            Mono<Map<Integer, Long>> mapMono = Flux.fromIterable(fetchPartitions(topic, consumer))
                                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                                    .collect(Collectors.toList())
                                    .flatMapIterable(topicPartitions -> fetchEndOffsets(topicPartitions, consumer).entrySet())
                                    .collectMap(e -> e.getKey().partition(), e -> e.getValue())
                                    ;
                            return Tuples.of(groupId.getVersion().orElseGet(() -> 0), mapMono.blockOptional().orElseGet(() -> Collections.emptyMap()));

                        }
                )
                .collectMap(Tuple2::getT1, Tuple2::getT2)
                .toFuture()
                ;
    }

    private Map<TopicPartition, Long> fetchEndOffsets(List<TopicPartition> topicPartitions, Consumer<?, ?> consumer) {
        synchronized (consumer) {
            return consumer.endOffsets(topicPartitions);
        }
    }

    private List<PartitionInfo> fetchPartitions(String topic, Consumer<?, ?> consumer) {
        synchronized (consumer) {
            return consumer.partitionsFor(topic);
        }
    }

    public static <T> CompletableFuture<T> makeCompletableFuture(Future<T> future) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
