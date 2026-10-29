package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class DuplicateRequestIT {

    @Autowired
    private ActivationRequestService service;

    @Autowired
    private ActivationRequestRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAllInBatch();
    }

    private static CreateActivationCommand command(String client, String externalId, String product) {
        return new CreateActivationCommand(client, externalId, "CUST-1", product, "corr");
    }

    @Test
    void sameExternalIdAndContentReturnsExistingWithoutNewRecord() {
        var first = service.create(command("system-a", "EXT-1", "INTERNET-500"));
        var retry = service.create(command("system-a", "EXT-1", "INTERNET-500"));

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.request().getId()).isEqualTo(first.request().getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdWithDifferentContentIsConflict() {
        var first = service.create(command("system-a", "EXT-1", "INTERNET-500"));

        assertThatThrownBy(() -> service.create(command("system-a", "EXT-1", "TV-100")))
                .isInstanceOf(DuplicateRequestConflictException.class)
                .extracting("existingId").isEqualTo(first.request().getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void externalIdIsScopedByConsumer() {
        service.create(command("system-a", "EXT-1", "INTERNET-500"));
        var other = service.create(command("system-b", "EXT-1", "INTERNET-500"));

        assertThat(other.created()).isTrue();
        assertThat(repository.count()).isEqualTo(2);
    }

    /**
     * Várias threads passam juntas pela consulta prévia (nenhuma encontra registro); só a constraint
     * do banco decide quem cria. Todas devem receber o mesmo registro e só uma deve "criar".
     */
    @Test
    void concurrentIdenticalRequestsCreateExactlyOneRecord() throws Exception {
        int threads = 16;
        var externalId = "EXT-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<Future<CreationResult>>();
            for (int i = 0; i < threads; i++) {
                Callable<CreationResult> task = () -> {
                    start.await();
                    return service.create(command("system-a", externalId, "INTERNET-500"));
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            var results = new ArrayList<CreationResult>();
            for (var future : futures) {
                results.add(future.get());
            }

            assertThat(results).extracting(r -> r.request().getId()).containsOnly(results.getFirst().request().getId());
            assertThat(results).filteredOn(CreationResult::created).hasSize(1);
            assertThat(repository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
