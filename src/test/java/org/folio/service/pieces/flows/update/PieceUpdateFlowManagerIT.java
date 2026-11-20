package org.folio.service.pieces.flows.update;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static org.folio.rest.core.exceptions.ErrorCodes.USER_HAS_NO_PERMISSIONS;
import static org.folio.TestConfig.autowireDependencies;
import static org.folio.TestConfig.clearServiceInteractions;
import static org.folio.TestConfig.clearVertxContext;
import static org.folio.TestConfig.getFirstContextFromVertx;
import static org.folio.TestConfig.getVertx;
import static org.folio.TestConfig.initSpringContext;
import static org.folio.TestConfig.isVerticleNotDeployed;
import static org.folio.TestConstants.ID;
import static org.folio.rest.jaxrs.model.Eresource.CreateInventory.INSTANCE_HOLDING_ITEM;
import static org.folio.service.inventory.InventoryHoldingManager.HOLDING_PERMANENT_LOCATION_ID;
import static org.folio.service.inventory.InventoryItemManager.ITEM_STATUS;
import static org.folio.service.inventory.InventoryItemManager.ITEM_STATUS_NAME;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.folio.ApiTestSuiteIT;
import org.folio.models.ItemStatus;
import org.folio.models.pieces.PieceUpdateHolder;
import org.folio.orders.utils.ProtectedOperationType;
import org.folio.rest.acq.model.ClaimSequence;
import org.folio.rest.acq.model.ClaimSequenceStage;
import org.folio.rest.core.RestClient;
import org.folio.rest.core.exceptions.ErrorCodes;
import org.folio.rest.core.models.RequestContext;
import org.folio.rest.jaxrs.model.Cost;
import org.folio.rest.jaxrs.model.Eresource;
import org.folio.rest.core.exceptions.HttpException;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.rest.jaxrs.model.PieceBatchStatusCollection;
import org.folio.rest.jaxrs.model.PieceClaiming;
import org.folio.rest.jaxrs.model.PieceClaimingSequence;
import org.folio.rest.jaxrs.model.PieceCollection;
import org.folio.rest.jaxrs.model.PoLine;
import org.folio.rest.jaxrs.model.PurchaseOrder;
import org.folio.rest.jaxrs.model.Title;
import org.folio.rest.jaxrs.model.acq.Location;
import org.folio.service.ClaimSequenceService;
import org.folio.service.ProtectionService;
import org.folio.service.caches.InventoryCache;
import org.folio.service.orders.PurchaseOrderLineService;
import org.folio.service.orders.PurchaseOrderStorageService;
import org.folio.service.pieces.PieceService;
import org.folio.service.pieces.PieceStorageService;
import org.folio.service.pieces.flows.BasePieceFlowHolderBuilder;
import org.folio.service.pieces.claiming.ClaimResolutionService;
import org.folio.service.pieces.flows.DefaultPieceFlowsValidator;
import org.folio.service.titles.TitlesService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;

@ExtendWith(VertxExtension.class)
public class PieceUpdateFlowManagerIT {
  @Autowired PieceUpdateFlowManager pieceUpdateFlowManager;
  @Autowired PieceStorageService pieceStorageService;
  @Autowired ProtectionService protectionService;
  @Autowired PieceUpdateFlowInventoryManager pieceUpdateFlowInventoryManager;
  @Autowired PieceService pieceService;
  @Autowired TitlesService titlesService;
  @Autowired BasePieceFlowHolderBuilder basePieceFlowHolderBuilder;
  @Autowired PieceUpdateFlowPoLineService pieceUpdateFlowPoLineService;
  @Autowired PurchaseOrderStorageService purchaseOrderStorageService;
  @Autowired PurchaseOrderLineService purchaseOrderLineService;
  @Autowired ClaimSequenceService claimSequenceService;

  private final Context ctx = getFirstContextFromVertx(getVertx());
  @Mock private Map<String, String> okapiHeadersMock;

  private RequestContext requestContext;
  private static boolean runningOnOwn;

  @BeforeEach
  void initMocks() {
    MockitoAnnotations.openMocks(this);
    autowireDependencies(this);
    requestContext = new RequestContext(ctx, okapiHeadersMock);
  }

  @BeforeAll
  public static void before() throws InterruptedException, ExecutionException, TimeoutException {
    if (isVerticleNotDeployed()) {
      ApiTestSuiteIT.before();
      runningOnOwn = true;
    }
    initSpringContext(PieceUpdateFlowManagerIT.ContextConfiguration.class);
  }

  @AfterAll
  public static void after() {
    clearVertxContext();
    if (runningOnOwn) {
      ApiTestSuiteIT.after();
    }
  }

  @AfterEach
  void resetMocks() {
    clearServiceInteractions();
    Mockito.reset(
        pieceStorageService,
        pieceService,
        protectionService,
        pieceUpdateFlowPoLineService,
        pieceUpdateFlowInventoryManager,
        basePieceFlowHolderBuilder,
        purchaseOrderLineService,
        claimSequenceService);
  }

  @Test
  void
      shouldUpdateLineQuantityIfPoLineIsNotPackageAndHoldingReferenceChangedAndShouldRunProcessInventory() {
    String orderId = UUID.randomUUID().toString();
    String oldHoldingId = UUID.randomUUID().toString();
    String holdingIdToUpdate = UUID.randomUUID().toString();
    String lineId = UUID.randomUUID().toString();
    String titleId = UUID.randomUUID().toString();
    String itemId = UUID.randomUUID().toString();
    String locationId = UUID.randomUUID().toString();
    String pieceId = UUID.randomUUID().toString();

    JsonObject item = new JsonObject().put(ID, itemId);
    item.put(ITEM_STATUS, new JsonObject().put(ITEM_STATUS_NAME, ItemStatus.ON_ORDER.value()));
    JsonObject holding = new JsonObject().put(ID, oldHoldingId);
    holding.put(HOLDING_PERMANENT_LOCATION_ID, locationId);
    Piece pieceFromStorage =
        new Piece()
            .withId(pieceId)
            .withPoLineId(lineId)
            .withItemId(itemId)
            .withTitleId(titleId)
            .withHoldingId(oldHoldingId)
            .withSequenceNumber(1)
            .withFormat(Piece.Format.ELECTRONIC);
    Piece incomingPieceToUpdate =
        new Piece()
            .withId(pieceId)
            .withPoLineId(lineId)
            .withItemId(itemId)
            .withTitleId(titleId)
            .withHoldingId(holdingIdToUpdate)
            .withSequenceNumber(1)
            .withFormat(Piece.Format.ELECTRONIC)
            .withReceivingStatus(Piece.ReceivingStatus.RECEIVED);
    Cost cost = new Cost().withQuantityElectronic(1);
    Location loc =
        new Location().withHoldingId(oldHoldingId).withQuantityElectronic(1).withQuantity(1);
    PoLine poLine =
        new PoLine()
            .withIsPackage(false)
            .withPurchaseOrderId(orderId)
            .withId(lineId)
            .withEresource(new Eresource().withCreateInventory(INSTANCE_HOLDING_ITEM))
            .withOrderFormat(PoLine.OrderFormat.ELECTRONIC_RESOURCE)
            .withLocations(List.of(loc))
            .withCost(cost);
    PurchaseOrder purchaseOrder =
        new PurchaseOrder().withId(orderId).withWorkflowStatus(PurchaseOrder.WorkflowStatus.OPEN);
    Title title = new Title().withId(titleId).withNextSequenceNumber(2);

    doReturn(succeededFuture(pieceFromStorage))
        .when(pieceStorageService)
        .getPieceById(incomingPieceToUpdate.getId(), requestContext);
    final ArgumentCaptor<Piece> pieceToUpdateCapture = ArgumentCaptor.forClass(Piece.class);
    doReturn(succeededFuture(null))
        .when(pieceStorageService)
        .updatePiece(pieceToUpdateCapture.capture(), eq(requestContext));
    givenPoLineHasPieces(lineId, List.of());
    doNothing().when(pieceService).receiptConsistencyPiecePoLine(anyString(), eq(requestContext));

    final ArgumentCaptor<PieceUpdateHolder> pieceUpdateHolderCapture =
        ArgumentCaptor.forClass(PieceUpdateHolder.class);
    doAnswer(
            (Answer<Future<Void>>)
                invocation -> {
                  PieceUpdateHolder answerHolder = invocation.getArgument(0);
                  answerHolder.withOrderInformation(purchaseOrder, poLine);
                  return succeededFuture(null);
                })
        .when(basePieceFlowHolderBuilder)
        .updateHolderWithOrderInformation(pieceUpdateHolderCapture.capture(), eq(requestContext));
    doAnswer(
            (Answer<Future<Void>>)
                invocation -> {
                  PieceUpdateHolder answerHolder = invocation.getArgument(0);
                  answerHolder.withTitleInformation(title);
                  return succeededFuture(null);
                })
        .when(basePieceFlowHolderBuilder)
        .updateHolderWithTitleInformation(pieceUpdateHolderCapture.capture(), eq(requestContext));

    doReturn(succeededFuture(null))
        .when(protectionService)
        .isOperationRestricted(any(), any(ProtectedOperationType.class), eq(requestContext));
    doReturn(succeededFuture(null))
        .when(pieceUpdateFlowInventoryManager)
        .processInventory(pieceUpdateHolderCapture.capture(), eq(requestContext));
    doReturn(succeededFuture(null))
        .when(pieceUpdateFlowPoLineService)
        .updatePoLineCostAndProcessEncumbrances(pieceUpdateHolderCapture.capture(), eq(requestContext));
    doReturn(succeededFuture(null))
        .when(pieceUpdateFlowPoLineService)
        .updateLocationsAndSavePoLine(pieceUpdateHolderCapture.capture(), eq(requestContext));

    // When
    pieceUpdateFlowManager.updatePiece(incomingPieceToUpdate, true, true, requestContext).result();
    // Then
    Piece pieceToUpdate = pieceToUpdateCapture.getValue();
    PieceUpdateHolder pieceUpdateHolder = pieceUpdateHolderCapture.getValue();

    assertNotNull(pieceToUpdate.getStatusUpdatedDate());
    verify(basePieceFlowHolderBuilder)
        .updateHolderWithOrderInformation(pieceUpdateHolderCapture.capture(), eq(requestContext));
    verify(pieceUpdateFlowPoLineService)
        .updatePoLineCostAndProcessEncumbrances(pieceUpdateHolderCapture.capture(), eq(requestContext));
    verify(pieceUpdateFlowInventoryManager)
        .processInventory(any(PieceUpdateHolder.class), eq(requestContext));
    verify(pieceStorageService).updatePiece(pieceToUpdate, requestContext);
  }

  @Test
  void shouldUpdatePiecesStatusesSuccessfully() {
    List<String> pieceIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    PieceBatchStatusCollection.ReceivingStatus receivingStatus =
        PieceBatchStatusCollection.ReceivingStatus.RECEIVED;
    Title title =
        new Title()
            .withId(UUID.randomUUID().toString())
            .withAcqUnitIds(List.of(UUID.randomUUID().toString()));
    PurchaseOrder purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString());
    PoLine poLine1 =
        new PoLine()
            .withId(UUID.randomUUID().toString())
            .withOrderFormat(PoLine.OrderFormat.P_E_MIX)
            .withPurchaseOrderId(purchaseOrder.getId());
    PoLine poLine2 =
        new PoLine()
            .withId(UUID.randomUUID().toString())
            .withOrderFormat(PoLine.OrderFormat.P_E_MIX)
            .withPurchaseOrderId(purchaseOrder.getId());
    Piece piece1 =
        new Piece()
            .withId(pieceIds.get(0))
            .withPoLineId(poLine1.getId())
            .withTitleId(title.getId());
    Piece piece2 =
        new Piece()
            .withId(pieceIds.get(1))
            .withPoLineId(poLine2.getId())
            .withTitleId(title.getId());

    doReturn(succeededFuture(List.of(piece1, piece2)))
        .when(pieceStorageService)
        .getPiecesByIds(pieceIds, requestContext);
    doReturn(succeededFuture(List.of(title)))
        .when(titlesService)
        .getTitlesByPieceIds(pieceIds, requestContext);
    doReturn(succeededFuture())
        .when(protectionService)
        .isOperationRestricted(
            title.getAcqUnitIds(), ProtectedOperationType.UPDATE, requestContext);
    doReturn(succeededFuture(poLine1))
        .when(purchaseOrderLineService)
        .getOrderLineById(poLine1.getId(), requestContext);
    doReturn(succeededFuture(poLine2))
        .when(purchaseOrderLineService)
        .getOrderLineById(poLine2.getId(), requestContext);
    doReturn(succeededFuture(purchaseOrder))
        .when(purchaseOrderStorageService)
        .getPurchaseOrderById(purchaseOrder.getId(), requestContext);
    doReturn(succeededFuture())
        .when(pieceUpdateFlowPoLineService)
        .updatePoLineCostAndProcessEncumbrances(any(), eq(requestContext));
    doReturn(succeededFuture())
        .when(pieceStorageService)
        .updatePiecesBatch(any(), eq(requestContext));
    doNothing().when(pieceService).receiptConsistencyPiecePoLine(anyString(), eq(requestContext));

    Future<Void> result =
        pieceUpdateFlowManager.updatePiecesStatuses(
            pieceIds, receivingStatus, null, null, null, requestContext);

    assertTrue(result.succeeded());
  }

  @Test
  void shouldSaveClaimingIntervalWhenPieceIsDelayedAgainWithSameStatus() {
    Piece piece = givenPieceForBatchStatusUpdate()
      .withReceivingStatus(Piece.ReceivingStatus.CLAIM_DELAYED)
      .withClaimingInterval(1);

    Future<Void> result = pieceUpdateFlowManager.updatePiecesStatuses(List.of(piece.getId()),
      PieceBatchStatusCollection.ReceivingStatus.CLAIM_DELAYED, 21, null, null, requestContext);

    assertTrue(result.succeeded());
    assertEquals(21, piece.getClaimingInterval());
    verify(pieceStorageService).updatePiecesBatch(any(), eq(requestContext));
    verify(pieceService, never()).receiptConsistencyPiecePoLine(anyString(), any());
  }

  @Test
  void shouldSaveNotesWhenPieceStatusIsNotChanged() {
    Piece piece = givenPieceForBatchStatusUpdate().withReceivingStatus(Piece.ReceivingStatus.LATE);

    Future<Void> result = pieceUpdateFlowManager.updatePiecesStatuses(List.of(piece.getId()),
      PieceBatchStatusCollection.ReceivingStatus.LATE, null, "internal", "external", requestContext);

    assertTrue(result.succeeded());
    assertEquals("internal", piece.getInternalNote());
    assertEquals("external", piece.getExternalNote());
    verify(pieceStorageService).updatePiecesBatch(any(), eq(requestContext));
    verify(pieceService, never()).receiptConsistencyPiecePoLine(anyString(), any());
  }

  @Test
  void shouldNotSavePiecesWhenNothingIsChanged() {
    Piece piece = givenPieceForBatchStatusUpdate().withReceivingStatus(Piece.ReceivingStatus.LATE);

    Future<Void> result = pieceUpdateFlowManager.updatePiecesStatuses(List.of(piece.getId()),
      PieceBatchStatusCollection.ReceivingStatus.LATE, null, null, null, requestContext);

    assertTrue(result.succeeded());
    verify(pieceStorageService, never()).updatePiecesBatch(any(), any());
    verify(pieceService, never()).receiptConsistencyPiecePoLine(anyString(), any());
  }

  private Piece givenPieceForBatchStatusUpdate() {
    PurchaseOrder purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString());
    PoLine poLine = new PoLine().withId(UUID.randomUUID().toString()).withPurchaseOrderId(purchaseOrder.getId());
    Title title = new Title().withId(UUID.randomUUID().toString());
    Piece piece = new Piece().withId(UUID.randomUUID().toString()).withPoLineId(poLine.getId()).withTitleId(title.getId());

    doReturn(succeededFuture(List.of(piece))).when(pieceStorageService).getPiecesByIds(List.of(piece.getId()), requestContext);
    doReturn(succeededFuture(List.of(title))).when(titlesService).getTitlesByPieceIds(List.of(piece.getId()), requestContext);
    doReturn(succeededFuture()).when(protectionService).isOperationRestricted(any(), eq(ProtectedOperationType.UPDATE), eq(requestContext));
    doReturn(succeededFuture(poLine)).when(purchaseOrderLineService).getOrderLineById(poLine.getId(), requestContext);
    doReturn(succeededFuture(purchaseOrder)).when(purchaseOrderStorageService).getPurchaseOrderById(purchaseOrder.getId(), requestContext);
    doReturn(succeededFuture()).when(pieceStorageService).updatePiecesBatch(any(), eq(requestContext));
    return piece;
  }

  @Test
  void shouldNotUpdatePiecesStatusesWhenTitleAcqUnitRestrictsUpdate() {
    Title title = new Title().withId(UUID.randomUUID().toString());
    Piece piece = new Piece()
      .withId(UUID.randomUUID().toString())
      .withTitleId(title.getId())
      .withReceivingStatus(Piece.ReceivingStatus.LATE);
    List<String> pieceIds = List.of(piece.getId());

    doReturn(succeededFuture(List.of(title))).when(titlesService).getTitlesByPieceIds(pieceIds, requestContext);
    doReturn(failedFuture(new HttpException(403, USER_HAS_NO_PERMISSIONS))).when(protectionService)
      .isOperationRestricted(any(), eq(ProtectedOperationType.UPDATE), eq(requestContext));

    Future<Void> result = pieceUpdateFlowManager.updatePiecesStatuses(pieceIds,
      PieceBatchStatusCollection.ReceivingStatus.CLAIM_DELAYED, 21, null, null, requestContext);

    assertTrue(result.failed());
    assertEquals(Piece.ReceivingStatus.LATE, piece.getReceivingStatus());
    verify(pieceStorageService, never()).getPiecesByIds(any(), any());
    verify(pieceStorageService, never()).updatePiecesBatch(any(), any());
  }

  @Test
  void shouldClaimPiecesWithNextClaimSequenceStageAndOthersWithRequestInterval() {
    var sequence = claimSequence(ClaimSequence.AfterLastStage.STOP, true);
    var title = new Title().withId(UUID.randomUUID().toString()).withAcqUnitIds(List.of());
    var purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString()).withVendor(UUID.randomUUID().toString());
    var sequencePoLine = poLine(purchaseOrder).withClaimSequenceId(sequence.getId());
    var otherPoLine = poLine(purchaseOrder);
    var firstClaim = latePiece(sequencePoLine, title);
    var secondClaim = latePiece(sequencePoLine, title).withClaiming(claimingAt(sequence.getId(), 1));
    var noSequencePiece = latePiece(otherPoLine, title).withClaiming(claimingAt(UUID.randomUUID().toString(), 3));
    var pieceIds = List.of(firstClaim.getId(), secondClaim.getId(), noSequencePiece.getId());
    givenClaimFlow(pieceIds, List.of(firstClaim, secondClaim, noSequencePiece), title, purchaseOrder,
        List.of(sequencePoLine, otherPoLine));
    doReturn(succeededFuture(List.of(sequence)))
        .when(claimSequenceService)
        .getClaimSequencesByIds(List.of(sequence.getId()), requestContext);

    var result = pieceUpdateFlowManager.updatePiecesStatuses(pieceIds,
        PieceBatchStatusCollection.ReceivingStatus.CLAIM_SENT, 7, "internal", "external", requestContext);

    assertTrue(result.succeeded());
    var writtenById = writtenPieces(2);
    assertEquals(3, writtenById.size());
    assertEquals(1, writtenById.get(firstClaim.getId()).getClaiming().getSequence().getStage());
    assertEquals(sequence.getId(), writtenById.get(firstClaim.getId()).getClaiming().getSequence().getId());
    assertEquals(10, writtenById.get(firstClaim.getId()).getClaimingInterval());
    // MANUAL stage: request notes are stored as for every other piece
    assertEquals("internal", writtenById.get(firstClaim.getId()).getInternalNote());
    assertEquals("external", writtenById.get(firstClaim.getId()).getExternalNote());
    assertEquals(2, writtenById.get(secondClaim.getId()).getClaiming().getSequence().getStage());
    assertEquals(20, writtenById.get(secondClaim.getId()).getClaimingInterval());
    assertEquals(Piece.ReceivingStatus.CLAIM_SENT, writtenById.get(secondClaim.getId()).getReceivingStatus());
    // Without sequence on the PO line: request interval, stored claiming sent back unchanged
    assertEquals(7, writtenById.get(noSequencePiece.getId()).getClaimingInterval());
    assertEquals(3, writtenById.get(noSequencePiece.getId()).getClaiming().getSequence().getStage());
  }

  @Test
  void shouldRejectBatchClaimSentAtIntegrationStageWithoutWriting() {
    var sequence = new ClaimSequence()
        .withId(UUID.randomUUID().toString())
        .withIsActive(true)
        .withStages(List.of(new ClaimSequenceStage().withStage(1).withName("Email claim").withInterval(10)
            .withDeliveryType(ClaimSequenceStage.DeliveryType.INTEGRATION)
            .withTransmissionMethod(ClaimSequenceStage.TransmissionMethod.EMAIL)));
    var title = new Title().withId(UUID.randomUUID().toString()).withAcqUnitIds(List.of());
    var vendorId = UUID.randomUUID().toString();
    var purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString()).withVendor(vendorId);
    var sequencePoLine = poLine(purchaseOrder).withClaimSequenceId(sequence.getId()).withAcquisitionMethod("purchase");
    var otherPoLine = poLine(purchaseOrder);
    var integrationPiece = latePiece(sequencePoLine, title);
    var noSequencePiece = latePiece(otherPoLine, title);
    var pieceIds = List.of(integrationPiece.getId(), noSequencePiece.getId());
    givenClaimFlow(pieceIds, List.of(integrationPiece, noSequencePiece), title, purchaseOrder,
        List.of(sequencePoLine, otherPoLine));
    doReturn(succeededFuture(List.of(sequence)))
        .when(claimSequenceService)
        .getClaimSequencesByIds(List.of(sequence.getId()), requestContext);

    var result = pieceUpdateFlowManager.updatePiecesStatuses(pieceIds,
        PieceBatchStatusCollection.ReceivingStatus.CLAIM_SENT, 7, null, null, requestContext);

    assertTrue(result.failed());
    var exception = (HttpException) result.cause();
    assertEquals(422, exception.getCode());
    var error = exception.getErrors().getErrors().getFirst();
    assertEquals(ErrorCodes.CLAIM_SEQUENCE_STAGE_REQUIRES_INTEGRATION.getCode(), error.getCode());
    var parameters = error.getParameters().stream().map(p -> p.getKey() + "=" + p.getValue()).toList();
    assertEquals(List.of("vendorId=" + vendorId, "acquisitionMethod=purchase", "transmissionMethod=Email",
        "pieceId=" + integrationPiece.getId()), parameters);
    verify(pieceStorageService, never()).updatePiecesBatch(any(), any());
  }

  @Test
  void shouldRejectClaimWithoutWritingWhenClaimSequenceIsCompletedInactiveOrNotFound() {
    var sequence = claimSequence(ClaimSequence.AfterLastStage.STOP, true);
    var inactiveSequence = claimSequence(ClaimSequence.AfterLastStage.STOP, false);
    var missingSequenceId = UUID.randomUUID().toString();
    var title = new Title().withId(UUID.randomUUID().toString()).withAcqUnitIds(List.of());
    var purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString()).withVendor(UUID.randomUUID().toString());
    var sequencePoLine = poLine(purchaseOrder).withClaimSequenceId(sequence.getId());
    var inactivePoLine = poLine(purchaseOrder).withClaimSequenceId(inactiveSequence.getId());
    var missingPoLine = poLine(purchaseOrder).withClaimSequenceId(missingSequenceId);
    var otherPoLine = poLine(purchaseOrder);
    var completedPiece = latePiece(sequencePoLine, title).withClaiming(claimingAt(sequence.getId(), 2));
    var inactivePiece = latePiece(inactivePoLine, title);
    var missingPiece = latePiece(missingPoLine, title);
    var noSequencePiece = latePiece(otherPoLine, title);
    var pieces = List.of(completedPiece, inactivePiece, missingPiece, noSequencePiece);
    var pieceIds = pieces.stream().map(Piece::getId).toList();
    givenClaimFlow(pieceIds, pieces, title, purchaseOrder, List.of(sequencePoLine, inactivePoLine, missingPoLine, otherPoLine));
    doReturn(succeededFuture(List.of(sequence, inactiveSequence)))
        .when(claimSequenceService)
        .getClaimSequencesByIds(any(), eq(requestContext));

    var result = pieceUpdateFlowManager.updatePiecesStatuses(pieceIds,
        PieceBatchStatusCollection.ReceivingStatus.CLAIM_SENT, 7, null, null, requestContext);

    assertTrue(result.failed());
    var exception = (HttpException) result.cause();
    assertEquals(400, exception.getCode());
    var errors = exception.getErrors().getErrors().stream()
        .collect(java.util.stream.Collectors.toMap(e -> e.getParameters().getFirst().getValue(), e -> e.getCode()));
    assertEquals(Map.of(
        completedPiece.getId(), ErrorCodes.CLAIM_SEQUENCE_COMPLETED.getCode(),
        inactivePiece.getId(), ErrorCodes.CLAIM_SEQUENCE_INACTIVE.getCode(),
        missingPiece.getId(), ErrorCodes.CLAIM_SEQUENCE_NOT_FOUND.getCode()), errors);
    // One storage request for all sequences of the request
    verify(claimSequenceService).getClaimSequencesByIds(any(), eq(requestContext));
    verify(pieceStorageService, never()).updatePiecesBatch(any(), any());
  }

  @Test
  void shouldNotTouchClaimSequenceForOtherTargetStatuses() {
    var sequence = claimSequence(ClaimSequence.AfterLastStage.STOP, true);
    var title = new Title().withId(UUID.randomUUID().toString()).withAcqUnitIds(List.of());
    var purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString()).withVendor(UUID.randomUUID().toString());
    var sequencePoLine = poLine(purchaseOrder).withClaimSequenceId(sequence.getId());
    var piece = latePiece(sequencePoLine, title).withClaiming(claimingAt(sequence.getId(), 1));
    givenClaimFlow(List.of(piece.getId()), List.of(piece), title, purchaseOrder, List.of(sequencePoLine));

    var result = pieceUpdateFlowManager.updatePiecesStatuses(List.of(piece.getId()),
        PieceBatchStatusCollection.ReceivingStatus.CLAIM_DELAYED, 7, null, null, requestContext);

    assertTrue(result.succeeded());
    verify(claimSequenceService, never()).getClaimSequencesByIds(any(), any());
    var written = writtenPieces(1).get(piece.getId());
    assertEquals(1, written.getClaiming().getSequence().getStage());
    assertEquals(7, written.getClaimingInterval());
  }

  private Map<String, Piece> writtenPieces(int expectedBatchCalls) {
    ArgumentCaptor<PieceCollection> batchCaptor = ArgumentCaptor.forClass(PieceCollection.class);
    verify(pieceStorageService, Mockito.times(expectedBatchCalls)).updatePiecesBatch(batchCaptor.capture(), eq(requestContext));
    return batchCaptor.getAllValues().stream()
        .flatMap(collection -> collection.getPieces().stream())
        .collect(java.util.stream.Collectors.toMap(Piece::getId, piece -> piece));
  }

  private void givenClaimFlow(List<String> pieceIds, List<Piece> pieces, Title title, PurchaseOrder purchaseOrder,
      List<PoLine> poLines) {
    doReturn(succeededFuture(pieces)).when(pieceStorageService).getPiecesByIds(pieceIds, requestContext);
    doReturn(succeededFuture(List.of(title))).when(titlesService).getTitlesByPieceIds(pieceIds, requestContext);
    doReturn(succeededFuture())
        .when(protectionService)
        .isOperationRestricted(title.getAcqUnitIds(), ProtectedOperationType.UPDATE, requestContext);
    poLines.forEach(poLine -> doReturn(succeededFuture(poLine))
        .when(purchaseOrderLineService)
        .getOrderLineById(poLine.getId(), requestContext));
    doReturn(succeededFuture(purchaseOrder))
        .when(purchaseOrderStorageService)
        .getPurchaseOrderById(purchaseOrder.getId(), requestContext);
    doReturn(succeededFuture()).when(pieceStorageService).updatePiecesBatch(any(), eq(requestContext));
    doNothing().when(pieceService).receiptConsistencyPiecePoLine(anyString(), eq(requestContext));
  }

  private static PieceClaiming claimingAt(String claimSequenceId, int stage) {
    return new PieceClaiming().withSequence(new PieceClaimingSequence().withId(claimSequenceId).withStage(stage));
  }

  private static PoLine poLine(PurchaseOrder purchaseOrder) {
    return new PoLine()
        .withId(UUID.randomUUID().toString())
        .withOrderFormat(PoLine.OrderFormat.P_E_MIX)
        .withPurchaseOrderId(purchaseOrder.getId());
  }

  private static Piece latePiece(PoLine poLine, Title title) {
    return new Piece()
        .withId(UUID.randomUUID().toString())
        .withPoLineId(poLine.getId())
        .withTitleId(title.getId())
        .withReceivingStatus(Piece.ReceivingStatus.LATE);
  }

  /**
   * Stage 1 MANUAL (10 days), stage 2 MANUAL (20 days): the batch status path allows manual stages only.
   */
  private static ClaimSequence claimSequence(ClaimSequence.AfterLastStage afterLastStage, boolean isActive) {
    return new ClaimSequence()
        .withId(UUID.randomUUID().toString())
        .withIsActive(isActive)
        .withAfterLastStage(afterLastStage)
        .withStages(List.of(
            new ClaimSequenceStage().withStage(1).withName("Phone").withInterval(10)
                .withDeliveryType(ClaimSequenceStage.DeliveryType.MANUAL),
            new ClaimSequenceStage().withStage(2).withName("Letter").withInterval(20)
                .withDeliveryType(ClaimSequenceStage.DeliveryType.MANUAL)));
  }

  private void givenPoLineHasPieces(String lineId, List<Piece> pieces) {
    doReturn(succeededFuture(pieces))
        .when(pieceStorageService)
        .getPiecesByLineId(eq(lineId), eq(requestContext));
  }

  private static class ContextConfiguration {
    @Bean
    PieceStorageService pieceStorageService() {
      return mock(PieceStorageService.class);
    }

    @Bean
    ProtectionService protectionService() {
      return mock(ProtectionService.class);
    }

    @Bean
    PieceService pieceService() {
      return mock(PieceService.class);
    }

    @Bean
    TitlesService titlesService() {
      return mock(TitlesService.class);
    }

    @Bean
    PieceUpdateFlowInventoryManager pieceUpdateFlowInventoryManager() {
      return mock(PieceUpdateFlowInventoryManager.class);
    }

    @Bean
    BasePieceFlowHolderBuilder basePieceFlowHolderBuilder(
        PurchaseOrderStorageService purchaseOrderStorageService,
        PurchaseOrderLineService purchaseOrderLineService,
        TitlesService titlesService) {
      return spy(
          new BasePieceFlowHolderBuilder(
              purchaseOrderStorageService, purchaseOrderLineService, titlesService));
    }

    @Bean
    PieceUpdateFlowPoLineService pieceUpdateFlowPoLineService() {
      return mock(PieceUpdateFlowPoLineService.class);
    }

    @Bean
    DefaultPieceFlowsValidator defaultPieceFlowsValidator() {
      return spy(new DefaultPieceFlowsValidator());
    }

    @Bean
    RestClient restClient() {
      return mock(RestClient.class);
    }

    @Bean
    InventoryCache inventoryCache() {
      return mock(InventoryCache.class);
    }

    @Bean
    PurchaseOrderStorageService purchaseOrderStorageService() {
      return mock(PurchaseOrderStorageService.class);
    }

    @Bean
    PurchaseOrderLineService purchaseOrderLineService() {
      return mock(PurchaseOrderLineService.class);
    }

    @Bean
    PieceUpdateFlowManager pieceUpdateFlowManager(
        PieceStorageService pieceStorageService,
        PieceService pieceService,
        TitlesService titlesService,
        ProtectionService protectionService,
        PieceUpdateFlowPoLineService pieceUpdateFlowPoLineService,
        PieceUpdateFlowInventoryManager pieceUpdateFlowInventoryManager,
        BasePieceFlowHolderBuilder basePieceFlowHolderBuilder,
        DefaultPieceFlowsValidator defaultPieceFlowsValidator,
        PurchaseOrderLineService purchaseOrderLineService,
        ClaimResolutionService claimResolutionService) {
      return new PieceUpdateFlowManager(
          pieceStorageService,
          pieceService,
          titlesService,
          protectionService,
          pieceUpdateFlowPoLineService,
          pieceUpdateFlowInventoryManager,
          basePieceFlowHolderBuilder,
          defaultPieceFlowsValidator,
          purchaseOrderLineService,
          claimResolutionService);
    }

    @Bean
    ClaimSequenceService claimSequenceService() {
      return mock(ClaimSequenceService.class);
    }

    @Bean
    ClaimResolutionService claimResolutionService(ClaimSequenceService claimSequenceService) {
      return new ClaimResolutionService(claimSequenceService);
    }
  }
}
