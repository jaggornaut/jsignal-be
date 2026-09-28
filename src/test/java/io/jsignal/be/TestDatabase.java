package io.jsignal.be;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class TestDatabase {

    private static final DockerImageName IMAGE = DockerImageName
            .parse("timescale/timescaledb:latest-pg16")
            .asCompatibleSubstituteFor("postgres");

    private TestDatabase() {
    }

    public static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>(IMAGE);
    }
}
