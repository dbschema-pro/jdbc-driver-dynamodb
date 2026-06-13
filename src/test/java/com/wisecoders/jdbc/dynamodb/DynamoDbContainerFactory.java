package com.wisecoders.jdbc.dynamodb;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a DynamoDB Local container and exposes the endpoint.
 * Placed in Java so the Kotlin compiler never needs to resolve GenericContainer's
 * JUnit-4 supertype (TestRule), which is excluded from this project's classpath.
 */
public final class DynamoDbContainerFactory {

    private static final GenericContainer<?> CONTAINER;

    static {
        CONTAINER = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
                .withExposedPorts(8000);
        CONTAINER.start();
    }

    public static String endpoint() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8000);
    }

    private DynamoDbContainerFactory() {}
}
