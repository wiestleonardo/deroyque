package com.deroyque.activation.service.impl;

import com.deroyque.activation.dto.CreateActivationRequestDTO;
import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import com.deroyque.activation.repository.ActivationRequestRepository;
import com.deroyque.activation.service.ActivationRequestService;
import com.deroyque.activation.service.AuthenticatedSystemService;
import com.deroyque.activation.support.IntegrationTest;
import com.deroyque.activation.vo.CreationResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class DuplicateActivationRequestIT {

    private static final String SYSTEM_A = "system-a";
    private static final Integer CONCURRENT_REQUESTS = 16;

    @Autowired
    private ActivationRequestService activationRequestService;

    @Autowired
    private ActivationRequestRepository activationRequestRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AuthenticatedSystemService authenticatedSystemService;

    @BeforeEach
    void cleanDatabase() {

        activationRequestRepository.deleteAllInBatch();
    }

    @Test
    void shouldReturnExistingRecordWithoutCreatingAnotherWhenRetryHasSameContent() {

        CreationResultVO firstCreation = this.createAs(SYSTEM_A, createDTO("EXT-1", "INTERNET-500"));
        CreationResultVO retry = this.createAs(SYSTEM_A, createDTO("EXT-1", "INTERNET-500"));

        assertThat(firstCreation.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.activationRequestDTO().id()).isEqualTo(firstCreation.activationRequestDTO().id());
        assertThat(activationRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldThrowDuplicateWhenExternalIdComesWithDifferentContent() {

        CreationResultVO firstCreation = this.createAs(SYSTEM_A, createDTO("EXT-1", "INTERNET-500"));

        assertThatThrownBy(() -> this.createAs(SYSTEM_A, createDTO("EXT-1", "TV-100")))
                .isInstanceOfSatisfying(BusinessServiceException.class, businessServiceException -> {

                    assertThat(businessServiceException.getErrorEnum()).isEqualTo(ErrorEnum.DUPLICATE_REQUEST);
                    assertThat(businessServiceException.getProperties()).containsEntry("existingRequestId", firstCreation.activationRequestDTO().id());
                });
        assertThat(activationRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldCreateAnotherRecordWhenExternalIdBelongsToAnotherSystem() {

        this.createAs(SYSTEM_A, createDTO("EXT-1", "INTERNET-500"));

        CreationResultVO otherSystemCreation = this.createAs("system-b", createDTO("EXT-1", "INTERNET-500"));

        assertThat(otherSystemCreation.created()).isTrue();
        assertThat(activationRequestRepository.count()).isEqualTo(2);
    }

    @Test
    void shouldCreateOnlyOneRecordWhenIdenticalRequestsArriveTogether() throws Exception {

        String externalId = "EXT-" + UUID.randomUUID();
        given(authenticatedSystemService.getSourceSystem()).willReturn("system-a");
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<CreationResultVO>> futureList = new ArrayList<>();

        try (ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {

            for (int request = 0; request < CONCURRENT_REQUESTS; request++) {

                futureList.add(executorService.submit(() -> {

                    startSignal.await();

                    return activationRequestService.create(createDTO(externalId, "INTERNET-500"));
                }));
            }

            startSignal.countDown();

            List<CreationResultVO> resultList = new ArrayList<>();

            for (Future<CreationResultVO> future : futureList) {

                resultList.add(future.get());
            }

            assertThat(resultList).extracting(result -> result.activationRequestDTO().id())
                    .containsOnly(resultList.getFirst().activationRequestDTO().id());
            assertThat(resultList).filteredOn(CreationResultVO::created).hasSize(1);
            assertThat(activationRequestRepository.count()).isEqualTo(1);
        }
    }

    private static CreateActivationRequestDTO createDTO(String externalId, String productCode) {

        return new CreateActivationRequestDTO(externalId, "CUST-1", productCode);
    }

    private CreationResultVO createAs(String sourceSystem, CreateActivationRequestDTO createActivationRequestDTO) {

        given(authenticatedSystemService.getSourceSystem()).willReturn(sourceSystem);

        return activationRequestService.create(createActivationRequestDTO);
    }

}
