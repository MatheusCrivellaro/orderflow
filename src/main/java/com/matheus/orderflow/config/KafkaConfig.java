package com.matheus.orderflow.config;

import com.matheus.orderflow.messaging.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class KafkaConfig {

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    /**
     * Cria os tópicos principais e seus Dead Letter Topics (*.DLT) na inicialização.
     */
    @Bean
    public KafkaAdmin.NewTopics topics() {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : Topics.ALL) {
            topics.add(TopicBuilder.name(topic).partitions(PARTITIONS).replicas(REPLICAS).build());
            topics.add(TopicBuilder.name(Topics.dlt(topic)).partitions(1).replicas(REPLICAS).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    /**
     * Retenta 3 vezes com intervalo de 1s. Se continuar falhando, envia a mensagem para o DLT.
     * Mensagens com JSON inválido vão direto para o DLT (não adianta retentar).
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(Topics.dlt(record.topic()), -1));
        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        errorHandler.addNotRetryableExceptions(JacksonException.class);
        return errorHandler;
    }
}
