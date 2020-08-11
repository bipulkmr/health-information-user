package in.org.projecteka.hiu.consent;

import com.google.common.collect.Sets;
import in.org.projecteka.hiu.ClientError;
import in.org.projecteka.hiu.Error;
import in.org.projecteka.hiu.ErrorRepresentation;
import in.org.projecteka.hiu.HiuProperties;
import in.org.projecteka.hiu.clients.GatewayServiceClient;
import in.org.projecteka.hiu.common.cache.CacheAdapter;
import in.org.projecteka.hiu.consent.model.Consent;
import in.org.projecteka.hiu.consent.model.ConsentRequestData;
import in.org.projecteka.hiu.consent.model.DateRange;
import in.org.projecteka.hiu.consent.model.HIType;
import in.org.projecteka.hiu.consent.model.Patient;
import in.org.projecteka.hiu.consent.model.PatientConsentRequest;
import in.org.projecteka.hiu.consent.model.Permission;
import in.org.projecteka.hiu.consent.model.Purpose;
import in.org.projecteka.hiu.consent.model.consentmanager.ConsentRequest;
import in.org.projecteka.hiu.dataflow.model.DataRequestStatus;
import in.org.projecteka.hiu.dataflow.model.PatientDataRequestDetail;
import in.org.projecteka.hiu.dataflow.model.PatientHealthInfoStatus;
import lombok.AllArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static in.org.projecteka.hiu.ErrorCode.INVALID_PURPOSE_OF_USE;
import static in.org.projecteka.hiu.common.Constants.EMPTY_STRING;
import static in.org.projecteka.hiu.common.Constants.PATIENT_REQUESTED_PURPOSE_CODE;
import static in.org.projecteka.hiu.common.Constants.getCmSuffix;
import static java.time.LocalDateTime.now;
import static java.time.ZoneOffset.UTC;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static reactor.core.publisher.Mono.defer;
import static reactor.core.publisher.Mono.just;

@AllArgsConstructor
public class PatientConsentService {
    private final PatientConsentRepository patientConsentRepository;
    private final ConsentServiceProperties consentServiceProperties;
    private final CacheAdapter<String, String> patientRequestCache;
    private final HiuProperties hiuProperties;
    private final ConceptValidator conceptValidator;
    private final GatewayServiceClient gatewayServiceClient;
    private final ConsentRepository consentRepository;
    private Function<List<String>, Flux<PatientHealthInfoStatus>> healthInfoStatus;

    public Mono<List<Map<String, Object>>> getLatestCareContextResourceDates(String patientId, String hipId) {
        return patientConsentRepository.getLatestResourceDateByHipCareContext(patientId, hipId);
    }

    public Mono<Map<String, String>> handlePatientConsentRequest(String requesterId,
                                                                 PatientConsentRequest consentRequest) {

        List<String> hipIds = filterEmptyAndNullValues(consentRequest.getHipIds());
        if (consentRequest.isReloadConsent()) {
            return Flux.fromIterable(hipIds)
                    .flatMap(hipId -> handleForReloadConsent(requesterId, hipId))
                    .flatMap(consentRequestData -> generateConsentRequestForSelf(consentRequestData)
                            .map(dataReqId -> Map.entry(consentRequestData.getConsent().getHipId(), dataReqId)))
                    .collectList()
                    .map(entries -> {
                        Map<String, String> response = new HashMap<>();
                        entries.forEach(e -> response.put(e.getKey(), e.getValue()));
                        return response;
                    });
        }

        return patientConsentRepository.getLatestDataRequestsForPatient(requesterId, hipIds)
                .flatMap(this::getStatusForPreviousRequests)
                .map(prevHipRequestStatuses -> {
                    var oldHips = prevHipRequestStatuses.stream().map(PatientHealthInfoStatus::getHipId).collect(Collectors.toSet());
                    var newHips = Sets.difference(Set.copyOf(hipIds), oldHips);
                    var finishedHips = prevHipRequestStatuses.stream()
                            .filter(drs -> !drs.getStatus().equals(DataRequestStatus.PROCESSING))
                            .map(PatientHealthInfoStatus::getHipId)
                            .collect(Collectors.toList());
                    finishedHips.addAll(newHips);
                    return finishedHips;
                })
                .flatMapMany(Flux::fromIterable)
                .flatMap(hipId -> buildConsentRequest(requesterId, hipId,
                        now(UTC).minusYears(consentServiceProperties.getConsentRequestFromYears())))
                .flatMap(consentRequestData -> generateConsentRequestForSelf(consentRequestData)
                        .map(dataReqId -> Map.entry(consentRequestData.getConsent().getHipId(), dataReqId)))
                .collectList()
                .map(entries -> {
                    Map<String, String> response = new HashMap<>();
                    entries.forEach(e -> response.put(e.getKey(), e.getValue()));
                    return response;
                });
    }

    private List<String> filterEmptyAndNullValues(List<String> ids) {
        return ids.stream().filter(Objects::nonNull).filter(n -> !n.equals(EMPTY_STRING)).collect(Collectors.toList());
    }

    private Mono<ConsentRequestData> handleForReloadConsent(String patientId, String hipId) {
        LocalDateTime now = now(UTC);

        return patientConsentRepository.deletePatientConsentRequestFor(patientId)
                .flatMap(patientConsentRepository::deleteConsentRequestFor)
                .flatMap(patientConsentRepository::deleteConsentArteFactFor)
                .flatMap(patientConsentRepository::deleteDataFlowRequestFor)
                .flatMap(patientConsentRepository::deleteHealthInformationFor)
                .flatMap(patientConsentRepository::deleteDataFlowPartsFor)
                .flatMap(patientConsentRepository::deleteDataFlowRequestKeysFor)
                .then(buildConsentRequest(patientId, hipId, now
                        .minusYears(consentServiceProperties.getConsentRequestFromYears())));
    }

    private Mono<ConsentRequestData> buildConsentRequest(String requesterId, String hipId, LocalDateTime dateFrom) {
        return just(ConsentRequestData.builder().consent(Consent.builder()
                .hiTypes(List.of(HIType.class.getEnumConstants()))
                .patient(Patient.builder().id(requesterId).build())
                .permission(Permission.builder().dataEraseAt(now(UTC)
                        .plusMonths(consentServiceProperties.getConsentExpiryInMonths()))
                        .dateRange(DateRange.builder()
                                .from(dateFrom)
                                .to(now(UTC)).build())
                        .build())
                .purpose(new Purpose(PATIENT_REQUESTED_PURPOSE_CODE))
                .hipId(hipId)
                .build())
                .build());
    }

    private Mono<String> generateConsentRequestForSelf(ConsentRequestData consentRequestData) {
        var dataRequestId = UUID.randomUUID();
        var gatewayRequestId = UUID.randomUUID();
        var hipIdForConsentRequest = consentRequestData.getConsent().getHipId();
        var patientId = consentRequestData.getConsent().getPatient().getId();
        return validateConsentRequest(consentRequestData)
                .then(sendConsentRequestToGateway(patientId, consentRequestData, gatewayRequestId))
                .then(patientConsentRepository
                        .insertPatientConsentRequest(dataRequestId, hipIdForConsentRequest, patientId)
                        .doOnSuccess(discard -> patientRequestCache.put(gatewayRequestId.toString(),
                                dataRequestId.toString()).subscribe())
                        .map(discard -> dataRequestId.toString()));
    }

    private Mono<Void> validateConsentRequest(ConsentRequestData consentRequestData) {
        return conceptValidator.validatePurpose(consentRequestData.getConsent().getPurpose().getCode())
                .filter(result -> result)
                .switchIfEmpty(Mono.error(new ClientError(INTERNAL_SERVER_ERROR,
                        new ErrorRepresentation(new Error(INVALID_PURPOSE_OF_USE,
                                "Invalid Purpose Of Use")))))
                .then();
    }

    private Mono<Void> sendConsentRequestToGateway(
            String requesterId,
            ConsentRequestData hiRequest,
            UUID gatewayRequestId) {
        var reqInfo = hiRequest.getConsent().to(requesterId, hiuProperties.getId(), conceptValidator);
        var patientId = hiRequest.getConsent().getPatient().getId();
        var consentRequest = ConsentRequest.builder()
                .requestId(gatewayRequestId)
                .timestamp(now(UTC))
                .consent(reqInfo)
                .build();
        var hiuConsentRequest = hiRequest.getConsent().toConsentRequest(gatewayRequestId.toString(), requesterId);
        return gatewayServiceClient.sendConsentRequest(getCmSuffix(patientId), consentRequest)
                .then(defer(() -> consentRepository.insertConsentRequestToGateway(hiuConsentRequest)));
    }

    private Mono<List<PatientHealthInfoStatus>> getStatusForPreviousRequests(List<PatientDataRequestDetail> dataRequestDetails) {
        var dataRequestIds = dataRequestDetails.stream().map(PatientDataRequestDetail::getDataRequestId).collect(Collectors.toList());
        return healthInfoStatus.apply(dataRequestIds).collectList();
    }
}