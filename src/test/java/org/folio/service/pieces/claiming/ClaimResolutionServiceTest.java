package org.folio.service.pieces.claiming;

import static org.folio.rest.core.exceptions.ErrorCodes.CLAIM_SEQUENCE_COMPLETED;
import static org.folio.rest.core.exceptions.ErrorCodes.CLAIM_SEQUENCE_INACTIVE;
import static org.folio.rest.core.exceptions.ErrorCodes.CLAIM_SEQUENCE_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.folio.rest.acq.model.ClaimSequence;
import org.folio.rest.acq.model.ClaimSequenceStage;
import org.folio.rest.acq.model.ClaimSequenceStage.DeliveryType;
import org.folio.rest.acq.model.ClaimSequenceStage.TransmissionMethod;
import org.folio.rest.core.exceptions.HttpException;
import org.folio.rest.core.models.RequestContext;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.rest.jaxrs.model.PieceClaiming;
import org.folio.rest.jaxrs.model.PieceClaimingSequence;
import org.folio.service.ClaimSequenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Future;

public class ClaimResolutionServiceTest {

  private final RequestContext requestContext = mock(RequestContext.class);
  private ClaimSequenceService claimSequenceService;
  private ClaimResolutionService claimResolutionService;

  @BeforeEach
  void setUp() {
    claimSequenceService = mock(ClaimSequenceService.class);
    claimResolutionService = new ClaimResolutionService(claimSequenceService);
  }

  @Test
  void resolvesPiecesWithoutSequenceWithoutStorageCall() {
    var piece = latePiece();

    var resolutions = claimResolutionService.resolve(List.of(new ClaimTarget(piece, null)), requestContext).result();

    assertSame(PieceClaimResolution.WITHOUT_SEQUENCE, resolutions.get(piece.getId()));
    assertEquals(DeliveryType.INTEGRATION, resolutions.get(piece.getId()).deliveryType());
    assertNull(resolutions.get(piece.getId()).transmissionMethod());
    verify(claimSequenceService, never()).getClaimSequencesByIds(any(), any());
  }

  @Test
  void resolvesManualAndIntegrationStagesWithOneStorageCall() {
    var manualSequence = sequence(true, stage(DeliveryType.MANUAL, null, 14));
    var ftpSequence = sequence(true, stage(DeliveryType.MANUAL, null, 14), stage(DeliveryType.INTEGRATION, TransmissionMethod.FTP, 30));
    var manualPiece = latePiece();
    var ftpPiece = latePiece().withClaiming(claimingAt(ftpSequence.getId(), 1));
    var ftpPiece2 = latePiece().withClaiming(claimingAt(ftpSequence.getId(), 1));
    var noSequencePiece = latePiece();
    when(claimSequenceService.getClaimSequencesByIds(any(), any()))
      .thenReturn(Future.succeededFuture(List.of(manualSequence, ftpSequence)));

    var resolutions = claimResolutionService.resolve(List.of(
      new ClaimTarget(manualPiece, manualSequence.getId()),
      new ClaimTarget(ftpPiece, ftpSequence.getId()),
      new ClaimTarget(ftpPiece2, ftpSequence.getId()),
      new ClaimTarget(noSequencePiece, null)), requestContext).result();

    var manual = resolutions.get(manualPiece.getId());
    assertTrue(manual.isManual());
    assertNull(manual.transmissionMethod());
    assertEquals(1, manual.stage());
    assertEquals(14, manual.claimingInterval(7));
    var ftp = resolutions.get(ftpPiece.getId());
    assertEquals(DeliveryType.INTEGRATION, ftp.deliveryType());
    assertEquals("FTP", ftp.transmissionMethodValue());
    assertEquals(2, ftp.stage());
    assertEquals(30, ftp.claimingInterval(7));
    assertEquals(7, resolutions.get(noSequencePiece.getId()).claimingInterval(7));
    verify(claimSequenceService).getClaimSequencesByIds(
      org.mockito.ArgumentMatchers.argThat(ids -> Set.copyOf(ids).equals(Set.of(manualSequence.getId(), ftpSequence.getId())) && ids.size() == 2),
      any());
  }

  @Test
  void skipsPiecesAlreadyClaimSent() {
    var sequence = sequence(true, stage(DeliveryType.MANUAL, null, 14));
    var claimSent = latePiece().withReceivingStatus(Piece.ReceivingStatus.CLAIM_SENT);

    var resolutions = claimResolutionService.resolve(List.of(new ClaimTarget(claimSent, sequence.getId())), requestContext).result();

    assertFalse(resolutions.containsKey(claimSent.getId()));
    verify(claimSequenceService, never()).getClaimSequencesByIds(any(), any());
  }

  @Test
  void collectsNotFoundInactiveAndCompletedErrors() {
    var activeSequence = sequence(true, stage(DeliveryType.MANUAL, null, 14));
    var inactiveSequence = sequence(false, stage(DeliveryType.MANUAL, null, 14));
    var missingSequenceId = UUID.randomUUID().toString();
    var completedPiece = latePiece().withClaiming(claimingAt(activeSequence.getId(), 1));
    var inactivePiece = latePiece();
    var missingPiece = latePiece();
    var okPiece = latePiece();
    when(claimSequenceService.getClaimSequencesByIds(any(), any()))
      .thenReturn(Future.succeededFuture(List.of(activeSequence, inactiveSequence)));

    var result = claimResolutionService.resolve(List.of(
      new ClaimTarget(completedPiece, activeSequence.getId()),
      new ClaimTarget(inactivePiece, inactiveSequence.getId()),
      new ClaimTarget(missingPiece, missingSequenceId),
      new ClaimTarget(okPiece, activeSequence.getId())), requestContext);

    assertTrue(result.failed());
    var exception = assertInstanceOf(HttpException.class, result.cause());
    assertEquals(400, exception.getCode());
    var codesByPiece = exception.getErrors().getErrors().stream()
      .collect(Collectors.toMap(error -> error.getParameters().getFirst().getValue(), error -> error.getCode()));
    assertEquals(Map.of(
      completedPiece.getId(), CLAIM_SEQUENCE_COMPLETED.getCode(),
      inactivePiece.getId(), CLAIM_SEQUENCE_INACTIVE.getCode(),
      missingPiece.getId(), CLAIM_SEQUENCE_NOT_FOUND.getCode()), codesByPiece);
    var missingError = exception.getErrors().getErrors().stream()
      .filter(error -> error.getCode().equals(CLAIM_SEQUENCE_NOT_FOUND.getCode())).findFirst().orElseThrow();
    assertEquals("claimSequenceId", missingError.getParameters().get(1).getKey());
    assertEquals(missingSequenceId, missingError.getParameters().get(1).getValue());
  }

  private static Piece latePiece() {
    return new Piece().withId(UUID.randomUUID().toString()).withReceivingStatus(Piece.ReceivingStatus.LATE);
  }

  private static PieceClaiming claimingAt(String claimSequenceId, int stage) {
    return new PieceClaiming().withSequence(new PieceClaimingSequence().withId(claimSequenceId).withStage(stage));
  }

  private static ClaimSequenceStage stage(DeliveryType deliveryType, TransmissionMethod transmissionMethod, int interval) {
    return new ClaimSequenceStage().withName(deliveryType + " " + interval).withDeliveryType(deliveryType)
      .withTransmissionMethod(transmissionMethod).withInterval(interval);
  }

  private static ClaimSequence sequence(boolean isActive, ClaimSequenceStage... stages) {
    return new ClaimSequence().withId(UUID.randomUUID().toString()).withIsActive(isActive)
      .withAfterLastStage(ClaimSequence.AfterLastStage.STOP).withStages(List.of(stages));
  }
}
