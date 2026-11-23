package org.folio.service.pieces;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.tuple.Pair;
import org.folio.HttpStatus;
import org.folio.models.claiming.ClaimJob;
import org.folio.models.claiming.ClaimPiece;
import org.folio.rest.acq.model.Organization;
import org.folio.rest.core.RestClient;
import org.folio.rest.core.exceptions.ErrorCodes;
import org.folio.rest.core.exceptions.HttpException;
import org.folio.rest.core.models.RequestContext;
import org.folio.rest.jaxrs.model.ClaimingCollection;
import org.folio.rest.jaxrs.model.ClaimingPieceResult;
import org.folio.rest.jaxrs.model.ClaimingResults;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.rest.jaxrs.model.PieceClaimingSequence;
import org.folio.service.caches.ExportConfigsCache;
import org.folio.service.orders.PurchaseOrderLineService;
import org.folio.service.orders.PurchaseOrderStorageService;
import org.folio.service.organization.OrganizationService;
import org.folio.service.pieces.claiming.ClaimConfigKey;
import org.folio.service.pieces.claiming.ClaimResolutionService;
import org.folio.service.pieces.claiming.ClaimSequenceErrors;
import org.folio.service.pieces.claiming.ClaimTarget;
import org.folio.service.pieces.claiming.PieceClaimResolution;
import org.folio.service.pieces.flows.update.PieceUpdateFlowManager;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.folio.models.claiming.IntegrationDetailField.CLAIM_PIECE_IDS;
import static org.folio.models.claiming.IntegrationDetailField.EXPORT_TYPE_SPECIFIC_PARAMETERS;
import static org.folio.models.claiming.IntegrationDetailField.JOB_ID;
import static org.folio.models.claiming.IntegrationDetailField.VENDOR_EDI_ORDERS_EXPORT_CONFIG;
import static org.folio.orders.utils.HelperUtils.collectResultsOnSuccess;
import static org.folio.orders.utils.ResourcePathResolver.DATA_EXPORT_SPRING_CREATE_JOB;
import static org.folio.orders.utils.ResourcePathResolver.resourcesPath;
import static org.folio.rest.core.exceptions.ErrorCodes.CANNOT_FIND_PIECES_WITH_LATE_STATUS_TO_PROCESS;
import static org.folio.rest.core.exceptions.ErrorCodes.CANNOT_FIND_PIECE_BY_ID;
import static org.folio.rest.core.exceptions.ErrorCodes.CANNOT_SEND_CLAIMS_PIECE_IDS_ARE_EMPTY;
import static org.folio.rest.core.exceptions.ErrorCodes.UNABLE_TO_GENERATE_CLAIMS_FOR_ORG_NO_INTEGRATION_DETAILS;
import static org.folio.rest.jaxrs.model.ClaimingPieceResult.Status.SUCCESS;
import static org.folio.rest.jaxrs.model.Piece.ReceivingStatus.LATE;
import static org.folio.rest.jaxrs.model.PieceBatchStatusCollection.ReceivingStatus.CLAIM_SENT;

@Log4j2
@Service
@RequiredArgsConstructor
public class PiecesClaimingService {

  private static final String VENDOR_CODE_PARAMETER = "vendorCode";
  private static final String PIECE_ID_PARAMETER = "pieceId";

  private final ExportConfigsCache exportConfigsCache;
  private final PieceStorageService pieceStorageService;
  private final PurchaseOrderLineService purchaseOrderLineService;
  private final PurchaseOrderStorageService purchaseOrderStorageService;
  private final OrganizationService organizationService;
  private final PieceUpdateFlowManager pieceUpdateFlowManager;
  private final RestClient restClient;
  private final ClaimResolutionService claimResolutionService;

  /**
   * @param claimJobs            one job per Claims export config, keyed by config id
   * @param piecesWithoutConfig  pieces without claim sequence for which no config applies
   * @param stagesWithoutConfig  pieces at an {@code INTEGRATION} stage for which no config with the stage's transmission
   *                             method applies, by (vendor, acquisition method, transmission method)
   */
  private record ClaimConfigs(Map<String, ClaimJob> claimJobs, List<ClaimPiece> piecesWithoutConfig,
                              Map<ClaimConfigKey, List<String>> stagesWithoutConfig) {
  }

  /**
   * Sends claims by receiving pieces to be claimed, works out the claim sequence stage of each piece, finds the Claims
   * export configs of each piece that is sent through an integration, updates piece statuses and finally creates one
   * job per export config with only the pieces it takes. Pieces at a {@code MANUAL} stage get no config and no job.
   *
   * @param claimingCollection An array of pieces ids
   * @param requestContext     Headers to make HTTP or Kafka requests
   * @return Future of an array of claimingResults
   */
  public Future<ClaimingResults> sendClaims(ClaimingCollection claimingCollection, RequestContext requestContext) {
    if (CollectionUtils.isEmpty(claimingCollection.getClaimingPieceIds())) {
      log.info("sendClaims:: Cannot send claims piece ids are empty - No claims are sent");
      throwHttpException(CANNOT_SEND_CLAIMS_PIECE_IDS_ARE_EMPTY, claimingCollection, HttpStatus.HTTP_BAD_REQUEST);
    }
    var pieceIds = claimingCollection.getClaimingPieceIds().stream().toList();
    log.info("sendClaims:: Received pieces to be claimed, pieceIds: {}", pieceIds);
    return loadClaimPieces(pieceIds, requestContext)
      .compose(claimPieces -> {
        if (CollectionUtils.isEmpty(claimPieces)) {
          log.info("sendClaims:: Cannot find pieces with late status to process - No claims are sent");
          throwHttpException(CANNOT_FIND_PIECES_WITH_LATE_STATUS_TO_PROCESS, claimingCollection, HttpStatus.HTTP_BAD_REQUEST);
        }
        var claimTargets = claimPieces.stream().map(piece -> new ClaimTarget(piece.piece(), piece.claimSequenceId())).toList();
        return claimResolutionService.resolve(claimTargets, requestContext)
          .compose(resolutions -> resolveConfigs(claimPieces, resolutions, requestContext)
            .compose(claimConfigs -> {
              validateConfigs(claimingCollection, claimConfigs);
              var latePieceIds = claimPieces.stream().map(ClaimPiece::pieceId).distinct().toList();
              return pieceUpdateFlowManager.isOperationRestricted(latePieceIds, requestContext)
                .compose(v -> updatePiecesAndCreateJobs(claimingCollection, claimPieces, claimConfigs.claimJobs(), resolutions, requestContext));
            }));
      })
      .onFailure(t -> log.error("sendClaims:: Failed send claims: {}", JsonObject.mapFrom(claimingCollection).encodePrettily(), t));
  }

  private Future<List<ClaimPiece>> loadClaimPieces(List<String> pieceIds, RequestContext requestContext) {
    log.info("loadClaimPieces:: Loading pieces to be claimed, pieceIds count: {}", pieceIds.size());
    return pieceStorageService.getPiecesByIds(pieceIds, requestContext)
      .compose(pieces -> {
        if (CollectionUtils.isEmpty(pieces)) {
          log.info("loadClaimPieces:: No pieces are found by piece ids, pieceIds: {}", pieceIds);
          return Future.succeededFuture(List.of());
        }
        var uniquePiecePoLinePairs = pieces.stream()
          .filter(Objects::nonNull).filter(piece -> Objects.nonNull(piece.getId()) && Objects.nonNull(piece.getPoLineId()))
          .map(piece -> Pair.of(piece.getPoLineId(), piece.getId())).distinct()
          .toList();
        log.info("loadClaimPieces:: Prepared unique piece-poLine pairs, pairs: {}", uniquePiecePoLinePairs);
        return collectResultsOnSuccess(createClaimPieceFutures(pieces, uniquePiecePoLinePairs, requestContext))
          .map(claimPieces -> claimPieces.stream().filter(Objects::nonNull).distinct().toList());
      });
  }

  private List<Future<ClaimPiece>> createClaimPieceFutures(List<Piece> pieces, List<Pair<String, String>> uniquePiecePoLinePairs,
                                                           RequestContext requestContext) {
    var claimPieceFutures = new ArrayList<Future<ClaimPiece>>();
    uniquePiecePoLinePairs.forEach(piecePoLinePairs -> {
      var foundPiece = pieces.stream()
        .filter(Objects::nonNull).filter(piece -> Objects.nonNull(piece.getId())).filter(piece -> piece.getId().equals(piecePoLinePairs.getRight()))
        .findFirst().orElseThrow(() -> new NoSuchElementException(String.format(CANNOT_FIND_PIECE_BY_ID.getDescription(), piecePoLinePairs.getRight())));
      claimPieceFutures.add(createClaimPiece(piecePoLinePairs, foundPiece, requestContext));
    });
    return claimPieceFutures;
  }

  private Future<ClaimPiece> createClaimPiece(Pair<String, String> piecePoLinePairs, Piece piece, RequestContext requestContext) {
    if (!piece.getReceivingStatus().equals(LATE)) {
      log.info("createClaimPiece:: Ignoring processing of a piece not in LATE state, piece id: {}", piece.getId());
      return Future.succeededFuture();
    }
    return purchaseOrderLineService.getOrderLineById(piecePoLinePairs.getLeft(), requestContext)
      .compose(poLine -> purchaseOrderStorageService.getPurchaseOrderById(poLine.getPurchaseOrderId(), requestContext)
        .compose(purchaseOrder -> organizationService.getVendorById(purchaseOrder.getVendor(), requestContext))
        .map(vendor -> {
          if (Objects.nonNull(vendor) && Boolean.TRUE.equals(vendor.getIsVendor())) {
            return new ClaimPiece(vendor, piece, poLine.getAcquisitionMethod(), poLine.getClaimSequenceId());
          }
          return null;
        }));
  }

  private Future<ClaimConfigs> resolveConfigs(List<ClaimPiece> claimPieces, Map<String, PieceClaimResolution> resolutions,
                                              RequestContext requestContext) {
    // Pieces at a MANUAL stage are claimed outside FOLIO: no config lookup, no job
    var piecesByConfigKey = claimPieces.stream()
      .filter(piece -> !resolutionOf(resolutions, piece.pieceId()).isManual())
      .collect(groupingBy(piece -> new ClaimConfigKey(piece.vendor().getId(), piece.acquisitionMethod(),
        resolutionOf(resolutions, piece.pieceId()).transmissionMethodValue()), LinkedHashMap::new, toList()));
    log.info("resolveConfigs:: Loading Claims export configs, vendor, acquisition method and transmission method combinations: {}, manual pieces: {}",
      piecesByConfigKey.size(), claimPieces.size() - piecesByConfigKey.values().stream().mapToInt(List::size).sum());
    var configsFutures = new ArrayList<Future<Pair<Map.Entry<ClaimConfigKey, List<ClaimPiece>>, JsonObject>>>();
    piecesByConfigKey.entrySet().forEach(entry -> configsFutures.add(
      exportConfigsCache.loadClaimsConfigsForPoLine(entry.getKey().vendorId(), entry.getKey().acquisitionMethod(),
          entry.getKey().transmissionMethod(), requestContext)
        .map(configs -> Pair.of(entry, configs))));
    return collectResultsOnSuccess(configsFutures).map(PiecesClaimingService::groupPiecesByConfig);
  }

  private static PieceClaimResolution resolutionOf(Map<String, PieceClaimResolution> resolutions, String pieceId) {
    return resolutions.getOrDefault(pieceId, PieceClaimResolution.WITHOUT_SEQUENCE);
  }

  private static ClaimConfigs groupPiecesByConfig(List<Pair<Map.Entry<ClaimConfigKey, List<ClaimPiece>>, JsonObject>> piecesWithConfigs) {
    var claimJobs = new LinkedHashMap<String, ClaimJob>();
    var piecesWithoutConfig = new ArrayList<ClaimPiece>();
    var stagesWithoutConfig = ClaimSequenceErrors.newStageGroups();
    piecesWithConfigs.forEach(piecesWithConfig -> {
      var configKey = piecesWithConfig.getLeft().getKey();
      var pieces = piecesWithConfig.getLeft().getValue();
      var configs = piecesWithConfig.getRight();
      if (configs.isEmpty()) {
        if (configKey.transmissionMethod() == null) {
          piecesWithoutConfig.addAll(pieces);
        } else {
          pieces.forEach(piece -> ClaimSequenceErrors.addPiece(stagesWithoutConfig, configKey, piece.pieceId()));
        }
      }
      // A piece goes to every config returned for it
      configs.fieldNames().forEach(configId -> {
        var config = configs.getJsonObject(configId);
        var pieceIds = claimJobs.computeIfAbsent(configId, id -> new ClaimJob(config, new ArrayList<>())).pieceIds();
        // Each piece has one config key, so a config returned for several keys gets each piece once
        pieces.forEach(piece -> pieceIds.add(piece.pieceId()));
      });
    });
    return new ClaimConfigs(claimJobs, piecesWithoutConfig, stagesWithoutConfig);
  }

  private void validateConfigs(ClaimingCollection claimingCollection, ClaimConfigs claimConfigs) {
    var errors = new ArrayList<Error>();
    if (!claimConfigs.piecesWithoutConfig().isEmpty()) {
      log.info("validateConfigs:: Pieces without Claims export config: {} - No claims are sent", claimConfigs.piecesWithoutConfig().size());
      errors.add(createMissingVendorIntegrationDetailsError(claimingCollection, claimConfigs.piecesWithoutConfig().getFirst().vendor()));
    }
    if (!claimConfigs.stagesWithoutConfig().isEmpty()) {
      log.info("validateConfigs:: Pieces at an integration stage without Claims export config for its transmission method, groups: {} - No claims are sent",
        claimConfigs.stagesWithoutConfig().size());
      errors.addAll(ClaimSequenceErrors.stageRequiresIntegrationErrors(claimConfigs.stagesWithoutConfig()));
    }
    if (!errors.isEmpty()) {
      throw ClaimSequenceErrors.unprocessable(errors);
    }
  }

  private Future<ClaimingResults> updatePiecesAndCreateJobs(ClaimingCollection claimingCollection, List<ClaimPiece> claimPieces,
                                                            Map<String, ClaimJob> claimJobs, Map<String, PieceClaimResolution> resolutions,
                                                            RequestContext requestContext) {
    var pieceIds = claimPieces.stream().map(ClaimPiece::pieceId).distinct().toList();
    log.info("updatePiecesAndCreateJobs:: Updating pieces and creating jobs, pieces: {}, configs: {}", pieceIds.size(), claimJobs.size());
    return updatePieces(claimingCollection, pieceIds, resolutions, requestContext)
      .compose(v -> createJobs(claimJobs, requestContext))
      .map(v -> {
        var successClaimingPieceResults = createSuccessClaimingResults(pieceIds, resolutions, claimingCollection.getClaimingInterval());
        log.info("updatePiecesAndCreateJobs:: Successfully processed pieces for claiming, count: {}", successClaimingPieceResults.size());
        var claimingResults = new ClaimingResults().withClaimingPieceResults(successClaimingPieceResults);
        log.debug("updatePiecesAndCreateJobs:: Returning claiming results, claimingResults: {}", JsonObject.mapFrom(claimingResults).encodePrettily());
        return claimingResults;
      });
  }

  private Future<List<Void>> createJobs(Map<String, ClaimJob> claimJobs, RequestContext requestContext) {
    var createJobFutures = new ArrayList<Future<Void>>();
    claimJobs.forEach((configId, claimJob) -> createJobFutures.add(createJob(configId, claimJob.config(), claimJob.pieceIds(), requestContext)));
    log.info("createJobs:: Creating one job per export config, total count: {}", createJobFutures.size());
    return collectResultsOnSuccess(createJobFutures);
  }

  private Future<Void> updatePieces(ClaimingCollection claimingCollection, List<String> pieceIds,
                                    Map<String, PieceClaimResolution> resolutions, RequestContext requestContext) {
    var claimingInterval = claimingCollection.getClaimingInterval();
    var internalNote = claimingCollection.getInternalNote();
    var externalNote = claimingCollection.getExternalNote();
    return pieceUpdateFlowManager.updatePiecesStatuses(pieceIds, CLAIM_SENT, claimingInterval, internalNote, externalNote,
      resolutions, requestContext);
  }

  private Future<Void> createJob(String configId, JsonObject config, List<String> pieceIds, RequestContext requestContext) {
    // The job gets its own id in the "id" field, so the config id is only kept in the logs
    var jobId = UUID.randomUUID().toString();
    log.info("createJob:: Creating a job, configId: {}, jobId: {}, pieces: {}", configId, jobId, pieceIds.size());
    var job = config.copy();
    job.put(JOB_ID.getValue(), jobId)
      .getJsonObject(EXPORT_TYPE_SPECIFIC_PARAMETERS.getValue())
      .getJsonObject(VENDOR_EDI_ORDERS_EXPORT_CONFIG.getValue())
      .put(CLAIM_PIECE_IDS.getValue(), pieceIds);
    return restClient.post(resourcesPath(DATA_EXPORT_SPRING_CREATE_JOB), job, Object.class, requestContext)
      .onSuccess(v -> log.info("createJob:: Created a job, configId: {}, jobId: {}, pieces: {}", configId, jobId, pieceIds.size()))
      .onFailure(t -> log.error("createJob:: Failed to create a job, configId: {}, jobId: {}", configId, jobId, t))
      .mapEmpty();
  }

  private List<ClaimingPieceResult> createSuccessClaimingResults(List<String> pieceIds, Map<String, PieceClaimResolution> resolutions,
                                                                 Integer requestClaimingInterval) {
    return pieceIds.stream()
      .map(pieceId -> createSuccessClaimingResult(pieceId, resolutionOf(resolutions, pieceId), requestClaimingInterval))
      .toList();
  }

  private static ClaimingPieceResult createSuccessClaimingResult(String pieceId, PieceClaimResolution resolution, Integer requestClaimingInterval) {
    var result = new ClaimingPieceResult().withPieceId(pieceId).withStatus(SUCCESS)
      .withDeliveryType(ClaimingPieceResult.DeliveryType.fromValue(resolution.deliveryType().value()))
      .withInterval(resolution.claimingInterval(requestClaimingInterval));
    if (resolution.transmissionMethod() != null) {
      result.setTransmissionMethod(ClaimingPieceResult.TransmissionMethod.fromValue(resolution.transmissionMethod().value()));
    }
    if (resolution.hasSequence()) {
      result.withSequence(new PieceClaimingSequence()
        .withId(resolution.claimSequenceId())
        .withStage(resolution.stage()));
    }
    return result;
  }

  private Error createMissingVendorIntegrationDetailsError(ClaimingCollection claimingCollection, Organization vendorWithoutIntegrationDetails) {
    var parameters = createPieceIdParameters(claimingCollection);
    parameters.add(new Parameter().withKey(VENDOR_CODE_PARAMETER).withValue(vendorWithoutIntegrationDetails.getCode()));
    return new Error().withCode(UNABLE_TO_GENERATE_CLAIMS_FOR_ORG_NO_INTEGRATION_DETAILS.getCode())
      .withMessage(String.format(UNABLE_TO_GENERATE_CLAIMS_FOR_ORG_NO_INTEGRATION_DETAILS.getDescription(), vendorWithoutIntegrationDetails.getCode()))
      .withParameters(parameters);
  }

  private void throwHttpException(ErrorCodes errorCode, ClaimingCollection claimingCollection, HttpStatus httpStatus) {
    var errors = List.of(new Error().withCode(errorCode.getCode())
      .withMessage(errorCode.getDescription())
      .withParameters(createPieceIdParameters(claimingCollection)));
    throw new HttpException(httpStatus.toInt(), new Errors().withErrors(errors).withTotalRecords(errors.size()));
  }

  private List<Parameter> createPieceIdParameters(ClaimingCollection claimingCollection) {
    return claimingCollection.getClaimingPieceIds().stream()
      .map(pieceId -> new Parameter().withKey(PIECE_ID_PARAMETER).withValue(pieceId))
      .collect(Collectors.toCollection(ArrayList::new));
  }
}
