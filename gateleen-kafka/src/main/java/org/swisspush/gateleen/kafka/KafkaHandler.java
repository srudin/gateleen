package org.swisspush.gateleen.kafka;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Handler class for all Kafka related requests.
 *
 * The main responsibilities for this handler are:
 * <ul>
 * <li>Manage kafka configuration resource</li>
 * <li>Manage the lifecycle of {@link KafkaProducer} based on the kafka configuration resource</li>
 * <li>Convert requests to messages and forward them to kafka</li>
 * </ul>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class KafkaHandler extends ConfigurationResourceConsumer {

    private final Logger log = LoggerFactory.getLogger(KafkaHandler.class);

    private final String streamingPath;
    private final KafkaProducerRepository repository;
    private final KafkaTopicExtractor topicExtractor;

    private boolean initialized = false;

    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, KafkaProducerRepository repository, String configResourceUri, String streamingPath) {
        super(configurationResourceManager, configResourceUri, "gateleen_kafka_topic_configuration_schema");
        this.repository = repository;
        this.streamingPath = streamingPath;

        this.topicExtractor = new KafkaTopicExtractor(streamingPath);
    }

    public Future<Void> initialize() {
        Future<Void> future = Future.future();
        configurationResourceManager().getRegisteredResource(configResourceUri()).setHandler(event -> {
            if (event.succeeded() && event.result().isPresent()) {
                initializeKafkaConfiguration(event.result().get()).setHandler(event1 -> future.complete());
            } else {
                log.warn("No kafka configuration resource with uri '{}' found. Unable to setup kafka configuration correctly", configResourceUri());
                future.complete();
            }
        });
        return future;
    }

    public boolean isInitialized() {
        return initialized;
    }

    private Future<Void> initializeKafkaConfiguration(Buffer configuration) {
        Future<Void> future = Future.future();
        final List<KafkaConfiguration> kafkaConfigurations = KafkaConfigurationParser.parse(configuration);
        repository.closeAll().setHandler(event -> {
            for (KafkaConfiguration kafkaConfiguration : kafkaConfigurations) {
                repository.addKafkaProducer(kafkaConfiguration);
            }
            initialized = true;
            future.complete();
        });

        return future;
    }

    public boolean handle(final HttpServerRequest request) {
        final Logger requestLog = RequestLoggerFactory.getLogger(KafkaHandler.class, request);

        if (request.uri().startsWith(streamingPath)) {
            requestLog.debug("Handling {}", request.uri());

            if (HttpMethod.POST != request.method()) {
                respondWith(StatusCode.METHOD_NOT_ALLOWED, StatusCode.METHOD_NOT_ALLOWED.getStatusMessage(), request);
                return true;
            }

            final Optional<String> optTopic = topicExtractor.extractTopic(request);
            if(!optTopic.isPresent()){
                respondWith(StatusCode.BAD_REQUEST, "Could not extract topic from request uri", request);
                return true;
            }

            String topic = optTopic.get();
            final Optional<Pair<KafkaProducer<String, String>, Pattern>> optProducer = repository.findMatchingKafkaProducer(topic);
            if(!optProducer.isPresent()){
                respondWith(StatusCode.NOT_FOUND, "Could not find a matching producer for topic " + topic, request);
                return true;
            }

            request.bodyHandler(payload -> {
                try {
                    final List<KafkaProducerRecord<String, String>> kafkaProducerRecords = KafkaProducerRecordBuilder.buildRecords(topic, payload);
                    //TODO send records
                    respondWith(StatusCode.OK, StatusCode.OK.getStatusMessage(), request);
                } catch (ValidationException ve){
                    respondWith(StatusCode.BAD_REQUEST, ve.getMessage(), request);
                }
            });
            return true;
        }
        return false;
    }

    @Override
    public void resourceChanged(String resourceUri, String resource) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Kafka configuration resource " + resourceUri + " was updated. Going to initialize with new configuration");
            initializeKafkaConfiguration(Buffer.buffer(resource));
        }
    }

    @Override
    public void resourceRemoved(String resourceUri) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Kafka configuration resource " + resourceUri + " was removed. Going to close all kafka producers");
            repository.closeAll().setHandler(event -> initialized = false);
        }
    }

    private void respondWith(StatusCode statusCode, String responseMessage, HttpServerRequest request) {
        ResponseStatusCodeLogUtil.info(request, statusCode, KafkaHandler.class);
        if(statusCode != StatusCode.OK) {
            RequestLoggerFactory.getLogger(KafkaHandler.class, request).info("Response message is: {}", responseMessage);
        }
        request.response().setStatusCode(statusCode.getStatusCode());
        request.response().setStatusMessage(statusCode.getStatusMessage());
        request.response().end(responseMessage);
    }
}
