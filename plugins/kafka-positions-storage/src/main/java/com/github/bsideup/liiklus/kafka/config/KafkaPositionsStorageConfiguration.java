package com.github.bsideup.liiklus.kafka.config;

import com.github.bsideup.liiklus.kafka.KafkaPositionsStorage;
import com.github.bsideup.liiklus.positions.PositionsStorage;
import com.google.auto.service.AutoService;
import lombok.Data;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Profiles;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotEmpty;

@AutoService(ApplicationContextInitializer.class)
public class KafkaPositionsStorageConfiguration implements ApplicationContextInitializer<GenericApplicationContext> {


    @Override
    public void initialize(GenericApplicationContext applicationContext) {
        var environment = applicationContext.getEnvironment();

        if (!environment.acceptsProfiles(Profiles.of("gateway"))) {
            return;
        }

        if (!"KAFKA".equals(environment.getProperty("storage.positions.type"))) {
            return;
        }

        var binder = Binder.get(environment);

        var kafkaProperties = binder.bind("kafka", KafkaProperties.class).get();

        applicationContext.registerBean(PositionsStorage.class, () ->
            new KafkaPositionsStorage(kafkaProperties.getBootstrapServers())
        );
    }

    @Data
    @Validated
    public static class KafkaProperties {

        @NotEmpty
        String bootstrapServers;
    }
}
