package org.folio.service.pieces;

import org.folio.CopilotGenerated;
import org.folio.models.pieces.PieceBatchStatusUpdateHolder;
import org.folio.rest.acq.model.ClaimSequenceStage;
import org.folio.rest.jaxrs.model.Eresource;
import org.folio.rest.jaxrs.model.Physical;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.rest.jaxrs.model.PieceClaim;
import org.folio.rest.jaxrs.model.PieceClaimSequence;
import org.folio.service.pieces.claiming.PieceClaimResolution;
import org.folio.rest.jaxrs.model.PoLine;
import org.folio.rest.jaxrs.model.acq.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@CopilotGenerated(model = "Claude Sonnet 4", partiallyGenerated = true)
public class PieceUtilTest {

  @Test
  void testUpdatePieceStatusWithOldAndNewStatus() {
    Piece pieceFromStorage = new Piece().withReceivingStatus(Piece.ReceivingStatus.EXPECTED);
    Piece.ReceivingStatus newStatus = Piece.ReceivingStatus.RECEIVED;
    Piece piece = new Piece().withReceivingStatus(newStatus);

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, pieceFromStorage);

    assertTrue(statusChanged);
    assertSame(newStatus, piece.getReceivingStatus());
    assertNotNull(piece.getStatusUpdatedDate());
  }

  @Test
  void testUpdatePieceStatusWithSameOldAndNewStatus() {
    Piece pieceFromStorage = new Piece().withReceivingStatus(Piece.ReceivingStatus.EXPECTED);
    Piece.ReceivingStatus newStatus = Piece.ReceivingStatus.EXPECTED;
    Piece piece = new Piece().withReceivingStatus(newStatus);

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, pieceFromStorage);

    assertFalse(statusChanged);
    assertSame(newStatus, piece.getReceivingStatus());
  }

  @ParameterizedTest
  @EnumSource(value = ClaimSequenceStage.DeliveryType.class)
  void testUpdatePieceStatusWithHolderSetsClaimSequenceStage(ClaimSequenceStage.DeliveryType deliveryType) {
    var claimSequenceId = UUID.randomUUID().toString();
    Piece piece = new Piece().withId(UUID.randomUUID().toString()).withReceivingStatus(Piece.ReceivingStatus.LATE);
    var stage = new ClaimSequenceStage().withStage(2).withInterval(45).withDeliveryType(deliveryType)
      .withTransmissionMethod(deliveryType == ClaimSequenceStage.DeliveryType.INTEGRATION ? ClaimSequenceStage.TransmissionMethod.FTP : null);
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.CLAIM_SENT,
      30, "internal", "external", List.of(piece), UUID.randomUUID().toString())
      .withClaimResolutions(Map.of(piece.getId(), PieceClaimResolution.ofStage(claimSequenceId, 2, stage)));

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertTrue(statusChanged);
    assertEquals(Piece.ReceivingStatus.CLAIM_SENT, piece.getReceivingStatus());
    assertEquals(45, piece.getClaimingInterval());
    assertEquals(claimSequenceId, piece.getClaim().getSequence().getId());
    assertEquals(2, piece.getClaim().getSequence().getStage());
    assertEquals("internal", piece.getInternalNote());
    assertEquals("external", piece.getExternalNote());
  }

  @Test
  void testUpdatePieceStatusWithHolderKeepsClaimSequenceWhenStatusUnchanged() {
    var claim = new PieceClaim().withSequence(new PieceClaimSequence().withId(UUID.randomUUID().toString()).withStage(1));
    Piece piece = new Piece().withId(UUID.randomUUID().toString()).withReceivingStatus(Piece.ReceivingStatus.CLAIM_SENT)
      .withClaim(claim);
    var stage = new ClaimSequenceStage().withStage(2).withInterval(45).withDeliveryType(ClaimSequenceStage.DeliveryType.MANUAL);
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.CLAIM_SENT,
      30, null, null, List.of(piece), UUID.randomUUID().toString())
      .withClaimResolutions(Map.of(piece.getId(), PieceClaimResolution.ofStage(claim.getSequence().getId(), 2, stage)));

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertFalse(statusChanged);
    assertSame(claim, piece.getClaim());
    assertEquals(1, piece.getClaim().getSequence().getStage());
    assertEquals(30, piece.getClaimingInterval());
  }

  @Test
  void testUpdatePieceStatusWithHolderWithoutSequenceKeepsRequestIntervalAndClaim() {
    var claim = new PieceClaim().withSequence(new PieceClaimSequence().withId(UUID.randomUUID().toString()).withStage(1));
    Piece piece = new Piece().withId(UUID.randomUUID().toString()).withReceivingStatus(Piece.ReceivingStatus.LATE).withClaim(claim);
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.CLAIM_SENT,
      30, null, null, List.of(piece), UUID.randomUUID().toString())
      .withClaimResolutions(Map.of(piece.getId(), PieceClaimResolution.WITHOUT_SEQUENCE));

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertTrue(statusChanged);
    assertEquals(30, piece.getClaimingInterval());
    assertSame(claim, piece.getClaim());
  }

  @Test
  void testUpdatePieceStatusWithHolderForOtherStatusLeavesClaimingAbsent() {
    Piece piece = new Piece().withId(UUID.randomUUID().toString()).withReceivingStatus(Piece.ReceivingStatus.LATE);
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.CLAIM_DELAYED,
      30, null, null, List.of(piece), UUID.randomUUID().toString());

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertTrue(statusChanged);
    assertEquals(30, piece.getClaimingInterval());
    assertNull(piece.getClaim());
  }

  @ParameterizedTest
  @EnumSource(value = Piece.ReceivingStatus.class, names = {"CLAIM_DELAYED", "CLAIM_SENT"})
  void testUpdatePieceStatusFromStorageWithReappliedClaimingInterval(Piece.ReceivingStatus status) {
    Date oldDate = new Date(0);
    Piece pieceFromStorage = new Piece().withReceivingStatus(status).withClaimingInterval(1).withStatusUpdatedDate(oldDate);
    Piece pieceToUpdate = new Piece().withReceivingStatus(status).withClaimingInterval(7).withStatusUpdatedDate(oldDate);

    boolean statusChanged = PieceUtil.updatePieceStatus(pieceToUpdate, pieceFromStorage);

    assertFalse(statusChanged);
    assertTrue(pieceToUpdate.getStatusUpdatedDate().after(oldDate));
  }

  @Test
  void testUpdatePieceStatusFromStorageWithSameClaimingInterval() {
    Date oldDate = new Date(0);
    Piece pieceFromStorage = new Piece().withReceivingStatus(Piece.ReceivingStatus.CLAIM_DELAYED).withClaimingInterval(7).withStatusUpdatedDate(oldDate);
    Piece pieceToUpdate = new Piece().withReceivingStatus(Piece.ReceivingStatus.CLAIM_DELAYED).withClaimingInterval(7).withStatusUpdatedDate(oldDate);

    boolean statusChanged = PieceUtil.updatePieceStatus(pieceToUpdate, pieceFromStorage);

    assertFalse(statusChanged);
    assertEquals(oldDate, pieceToUpdate.getStatusUpdatedDate());
  }

  @Test
  void testUpdatePieceStatusFromStorageWithChangedStatus() {
    Piece pieceFromStorage = new Piece().withReceivingStatus(Piece.ReceivingStatus.LATE);
    Piece pieceToUpdate = new Piece().withReceivingStatus(Piece.ReceivingStatus.CLAIM_DELAYED).withClaimingInterval(3);

    assertTrue(PieceUtil.updatePieceStatus(pieceToUpdate, pieceFromStorage));
    assertNotNull(pieceToUpdate.getStatusUpdatedDate());
  }

  @Test
  void testUpdatePieceStatusWithHolder() {
    Piece piece = new Piece().withReceivingStatus(Piece.ReceivingStatus.EXPECTED);
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.RECEIVED,
      30, "internal", "external", List.of(piece), UUID.randomUUID().toString());

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertTrue(statusChanged);
    assertSame(piece.getReceivingStatus(), holder.getReceivingStatus());
    assertSame(piece.getClaimingInterval(), holder.getClaimingInterval());
    assertEquals(piece.getInternalNote(), holder.getInternalNote());
    assertEquals(piece.getExternalNote(), holder.getExternalNote());
    assertNotNull(piece.getStatusUpdatedDate());
  }

  @Test
  void testUpdatePieceStatusWithHolderSameStatus() {
    Piece piece = new Piece().withReceivingStatus(Piece.ReceivingStatus.EXPECTED);
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.EXPECTED,
      30, "internal", "external", List.of(piece), UUID.randomUUID().toString());

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertFalse(statusChanged);
    assertSame(piece.getReceivingStatus(), holder.getReceivingStatus());
    assertSame(piece.getClaimingInterval(), holder.getClaimingInterval());
    assertEquals(piece.getInternalNote(), holder.getInternalNote());
    assertEquals(piece.getExternalNote(), holder.getExternalNote());
  }

  @Test
  void testUpdatePieceStatusWithHolderKeepsExistingValuesWhenNotProvided() {
    Piece piece = new Piece().withReceivingStatus(Piece.ReceivingStatus.LATE)
      .withClaimingInterval(5).withInternalNote("internal").withExternalNote("external");
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.UNRECEIVABLE,
      null, null, null, List.of(piece), UUID.randomUUID().toString());

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertTrue(statusChanged);
    assertEquals(Piece.ReceivingStatus.UNRECEIVABLE, piece.getReceivingStatus());
    assertEquals(5, piece.getClaimingInterval());
    assertEquals("internal", piece.getInternalNote());
    assertEquals("external", piece.getExternalNote());
  }

  @Test
  void testUpdatePieceStatusWithHolderResetsStatusUpdatedDateWhenClaimIsDelayedAgain() {
    Date previousStatusUpdatedDate = new Date(0);
    Piece piece = new Piece().withReceivingStatus(Piece.ReceivingStatus.CLAIM_DELAYED)
      .withClaimingInterval(1).withStatusUpdatedDate(previousStatusUpdatedDate);
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.CLAIM_DELAYED,
      21, null, null, List.of(piece), UUID.randomUUID().toString());

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertFalse(statusChanged);
    assertEquals(21, piece.getClaimingInterval());
    assertTrue(piece.getStatusUpdatedDate().after(previousStatusUpdatedDate));
  }

  @Test
  void testUpdatePieceStatusWithHolderKeepsStatusUpdatedDateWhenNothingChanged() {
    Date previousStatusUpdatedDate = new Date(0);
    Piece piece = new Piece().withReceivingStatus(Piece.ReceivingStatus.CLAIM_DELAYED)
      .withClaimingInterval(1).withStatusUpdatedDate(previousStatusUpdatedDate);
    PieceBatchStatusUpdateHolder holder = new PieceBatchStatusUpdateHolder(Piece.ReceivingStatus.CLAIM_DELAYED,
      null, "internal", null, List.of(piece), UUID.randomUUID().toString());

    boolean statusChanged = PieceUtil.updatePieceStatus(piece, holder);

    assertFalse(statusChanged);
    assertEquals(previousStatusUpdatedDate, piece.getStatusUpdatedDate());
  }

  @ParameterizedTest
  @EnumSource(value = Piece.Format.class, names = { "PHYSICAL", "OTHER" })
  void returnsExpectedReceiptDateWhenPieceFormatIsPhysicalOrOtherAndPhysicalIsPresent(Piece.Format format) {
    PoLine poLine = new PoLine().withPhysical(new Physical().withExpectedReceiptDate(new Date()));

    Date result = PieceUtil.getExpectedReceiptDate(format, poLine);

    assertEquals(poLine.getPhysical().getExpectedReceiptDate(), result);
  }

  @Test
  void returnsNullWhenPieceFormatIsPhysicalAndPhysicalIsNull() {
    PoLine poLine = new PoLine();

    Date result = PieceUtil.getExpectedReceiptDate(Piece.Format.PHYSICAL, poLine);

    assertNull(result);
  }

  @Test
  void returnsExpectedActivationWhenPieceFormatIsElectronic() {
    Date expectedReceiptDate = new Date(1_000_000L);
    Date expectedActivation = new Date(2_000_000L);
    PoLine poLine = new PoLine()
      .withPhysical(new Physical().withExpectedReceiptDate(expectedReceiptDate))
      .withEresource(new Eresource().withExpectedActivation(expectedActivation));

    Date result = PieceUtil.getExpectedReceiptDate(Piece.Format.ELECTRONIC, poLine);

    assertEquals(expectedActivation, result);
  }

  @Test
  void returnsNullWhenPieceFormatIsElectronicAndEresourceIsNull() {
    PoLine poLine = new PoLine().withPhysical(new Physical().withExpectedReceiptDate(new Date()));

    Date result = PieceUtil.getExpectedReceiptDate(Piece.Format.ELECTRONIC, poLine);

    assertNull(result);
  }

  @Test
  void returnsNullWhenPieceFormatIsElectronicAndExpectedActivationIsNull() {
    PoLine poLine = new PoLine()
      .withPhysical(new Physical().withExpectedReceiptDate(new Date()))
      .withEresource(new Eresource());

    Date result = PieceUtil.getExpectedReceiptDate(Piece.Format.ELECTRONIC, poLine);

    assertNull(result);
  }

  @Test
  void testGetPiecesLocationsWithEmptyList() {
    List<Piece> pieces = List.of();

    List<Location> result = PieceUtil.getPiecesLocations(pieces);

    assertTrue(result.isEmpty());
  }

  @Test
  void testGetPiecesLocationsWithNonEmptyList() {
    String holdingId = UUID.randomUUID().toString();
    String locationId = UUID.randomUUID().toString();
    String tenantId = "test-tenant";

    Piece piece = new Piece()
      .withHoldingId(holdingId)
      .withLocationId(locationId)
      .withReceivingTenantId(tenantId);

    List<Piece> pieces = List.of(piece);

    List<Location> result = PieceUtil.getPiecesLocations(pieces);

    assertEquals(1, result.size());
    Location location = result.getFirst();
    assertEquals(holdingId, location.getHoldingId());
    assertEquals(locationId, location.getLocationId());
    assertEquals(tenantId, location.getTenantId());
  }

}
