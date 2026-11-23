package org.folio.service.pieces.claiming;

import static org.folio.rest.core.exceptions.ErrorCodes.CLAIM_SEQUENCE_COMPLETED;
import static org.folio.rest.core.exceptions.ErrorCodes.CLAIM_SEQUENCE_INACTIVE;
import static org.folio.rest.core.exceptions.ErrorCodes.CLAIM_SEQUENCE_NOT_FOUND;
import static org.folio.service.pieces.claiming.ClaimSequenceErrors.CLAIM_SEQUENCE_ID_PARAMETER;
import static org.folio.service.pieces.claiming.ClaimSequenceErrors.PIECE_ID_PARAMETER;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.folio.HttpStatus;
import org.folio.rest.acq.model.ClaimSequence;
import org.folio.rest.core.exceptions.ErrorCodes;
import org.folio.rest.core.exceptions.HttpException;
import org.folio.rest.core.models.RequestContext;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.service.ClaimSequenceService;
import org.folio.service.pieces.ClaimSequenceStageResolver;

import io.vertx.core.Future;
import lombok.extern.log4j.Log4j2;

/**
 * Works out, once per request and before anything is written, how each piece changing into 'Claim sent' is claimed:
 * without sequence (today's behaviour) or at the next stage of its PO line's claim sequence.
 */
@Log4j2
public class ClaimResolutionService {

  private final ClaimSequenceService claimSequenceService;

  public ClaimResolutionService(ClaimSequenceService claimSequenceService) {
    this.claimSequenceService = claimSequenceService;
  }

  /**
   * Loads the claim sequences of all targets with one storage request and resolves the next stage per piece.
   * Pieces already in 'Claim sent' are skipped (their claim sequence position never changes).
   *
   * @return resolution per piece id, {@link PieceClaimResolution#WITHOUT_SEQUENCE} for pieces whose PO line has no
   * claim sequence; fails with 400 listing every {@code claimSequenceNotFound} / {@code claimSequenceInactive} /
   * {@code claimSequenceCompleted} error of the request
   */
  public Future<Map<String, PieceClaimResolution>> resolve(List<ClaimTarget> targets, RequestContext requestContext) {
    var claimTargets = targets.stream()
      .filter(target -> target.piece().getReceivingStatus() != Piece.ReceivingStatus.CLAIM_SENT)
      .toList();
    var claimSequenceIds = claimTargets.stream()
      .map(ClaimTarget::claimSequenceId)
      .filter(Objects::nonNull)
      .distinct()
      .toList();
    if (claimSequenceIds.isEmpty()) {
      return resolve(claimTargets, Map.of());
    }
    return claimSequenceService.getClaimSequencesByIds(claimSequenceIds, requestContext)
      .map(sequences -> sequences.stream().collect(Collectors.toMap(ClaimSequence::getId, Function.identity(), (a, b) -> a)))
      .compose(sequencesById -> resolve(claimTargets, sequencesById));
  }

  private Future<Map<String, PieceClaimResolution>> resolve(List<ClaimTarget> targets, Map<String, ClaimSequence> sequencesById) {
    var resolutions = new LinkedHashMap<String, PieceClaimResolution>();
    var errors = new ArrayList<Error>();
    targets.forEach(target -> {
      var pieceId = target.piece().getId();
      var claimSequenceId = target.claimSequenceId();
      if (claimSequenceId == null) {
        resolutions.put(pieceId, PieceClaimResolution.WITHOUT_SEQUENCE);
        return;
      }
      var sequence = sequencesById.get(claimSequenceId);
      if (sequence == null) {
        errors.add(sequenceError(CLAIM_SEQUENCE_NOT_FOUND, pieceId, claimSequenceId));
        return;
      }
      if (Boolean.FALSE.equals(sequence.getIsActive())) {
        errors.add(sequenceError(CLAIM_SEQUENCE_INACTIVE, pieceId, claimSequenceId));
        return;
      }
      var stageResolution = ClaimSequenceStageResolver.resolve(target.piece(), sequence);
      if (stageResolution.isCompleted()) {
        errors.add(sequenceError(CLAIM_SEQUENCE_COMPLETED, pieceId, claimSequenceId));
        return;
      }
      resolutions.put(pieceId, PieceClaimResolution.ofStage(claimSequenceId, stageResolution.stageNumber(), stageResolution.stage()));
    });
    if (!errors.isEmpty()) {
      log.info("resolve:: Pieces cannot be claimed by their claim sequence, errors: {}", errors.size());
      return Future.failedFuture(new HttpException(HttpStatus.HTTP_BAD_REQUEST.toInt(),
        new Errors().withErrors(errors).withTotalRecords(errors.size())));
    }
    log.info("resolve:: Resolved claims, pieces: {}, by claim sequence: {}", resolutions.size(),
      resolutions.values().stream().filter(PieceClaimResolution::hasSequence).count());
    return Future.succeededFuture(resolutions);
  }

  private static Error sequenceError(ErrorCodes errorCode, String pieceId, String claimSequenceId) {
    return errorCode.toError().withParameters(List.of(
      new Parameter().withKey(PIECE_ID_PARAMETER).withValue(pieceId),
      new Parameter().withKey(CLAIM_SEQUENCE_ID_PARAMETER).withValue(claimSequenceId)));
  }
}
