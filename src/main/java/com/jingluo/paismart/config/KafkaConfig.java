package com.jingluo.paismart.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 16:47
 * @Desc: Kafka 配置类：声明文件处理与死信主题，配置事务生产者、JSON 序列化及支持重试与死信队列的消费者监听器工厂
 */
@Data
@Configuration
public class KafkaConfig {

    /**
     * Kafka 集群连接地址
     */
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * 文件处理任务主题名称
     */
    @Value("${spring.kafka.topic.file-processing}")
    private String fileProcessingTopic;

    /**
     * 文件处理任务的死信主题名称
     */
    @Value("${spring.kafka.topic.dlt}")
    private String fileProcessingDltTopic;

    /**
     * 主题分区数，默认 1
     */
    @Value("${spring.kafka.topic.partitions:1}")
    private int topicPartitions;

    /**
     * 主题副本数，默认 1
     */
    @Value("${spring.kafka.topic.replication-factor:1}")
    private short topicReplicationFactor;

    /**
     * 文件处理消费者组 ID
     */
    @Value("${spring.kafka.consumer.group-id}")
    private String fileProcessingGroupId;

    /**
     * JSON 反序列化信任的包名列表
     */
    @Value("${spring.kafka.consumer.properties.spring.json.trusted.packages}")
    private String trustedPackages;

    /**
     * 声明文件处理主题，应用启动时自动创建
     */
    @Bean
    public NewTopic fileProcessingNewTopic() {
        return TopicBuilder.name(fileProcessingTopic).partitions(topicPartitions).replicas(topicReplicationFactor)
            .build();
    }

    /**
     * 声明文件处理死信主题，多次重试仍失败的消息最终进入该主题
     */
    @Bean
    public NewTopic fileProcessingDltNewTopic() {
        return TopicBuilder.name(fileProcessingDltTopic).partitions(topicPartitions).replicas(topicReplicationFactor)
            .build();
    }

    /**
     * 生产者工厂：使用 JSON 序列化，开启幂等与事务，保证任务消息可靠投递
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // 可靠投递配置
        // 全部 ISR 落盘才确认
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // 幂等生产者
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // 自动重试 3 次
        config.put(ProducerConfig.RETRIES_CONFIG, 3);

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(config);
        // 设置事务前缀，启用事务能力
        factory.setTransactionIdPrefix("file-upload-tx-");
        return factory;
    }

    /**
     * KafkaTemplate：基于事务生产者工厂构建，发送时可使用事务
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * 消费者工厂：使用 JSON 反序列化并绑定文件处理消费者组
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, fileProcessingGroupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, trustedPackages);

        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * 带自动重试和死信队列的监听器工厂
     *
     * @param consumerFactory
     * @param kafkaTemplate
     * @return
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
        ConsumerFactory<String, Object> consumerFactory, KafkaTemplate<String, Object> kafkaTemplate) {
        // 当重试失败后，消息发送至 file-processing-dlt 主题，分区与原消息保持一致
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
            (record, ex) -> new TopicPartition(fileProcessingDltTopic, record.partition()));

        // 固定退避策略：每 3 秒重试一次，最多重试 4 次（加首次共 5 次）
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(3000L, 4));

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
