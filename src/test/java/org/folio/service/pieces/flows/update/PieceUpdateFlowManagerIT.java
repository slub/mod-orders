package org.folio.service.pieces.flows.update;

import static io.vertx.core.Future.succeededFuture;
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
import org.folio.rest.core.exceptions.HttpException;
import org.folio.rest.core.models.RequestContext;
import org.folio.rest.jaxrs.model.Cost;
import org.folio.rest.jaxrs.model.Eresource;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.rest.jaxrs.model.PieceBatchStatusCollection;
import org.folio.rest.jaxrs.model.PieceClaimSequence;
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
  void shouldClaimPiecesWithNextClaimSequenceStageAndOthersWithRequestInterval() {
    var sequence = claimSequence(ClaimSequence.AfterLastStage.STOP, true);
    var title = new Title().withId(UUID.randomUUID().toString()).withAcqUnitIds(List.of());
    var purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString());
    var sequencePoLine = poLine(purchaseOrder).withClaimSequenceId(sequence.getId());
    var otherPoLine = poLine(purchaseOrder);
    var firstClaim = latePiece(sequencePoLine, title);
    var secondClaim = latePiece(sequencePoLine, title)
        .withClaimSequence(new PieceClaimSequence().withId(sequence.getId()).withStage(1));
    var noSequencePiece = latePiece(otherPoLine, title);
    var pieceIds = List.of(firstClaim.getId(), secondClaim.getId(), noSequencePiece.getId());
    givenClaimFlow(pieceIds, List.of(firstClaim, secondClaim, noSequencePiece), title, purchaseOrder,
        List.of(sequencePoLine, otherPoLine));
    doReturn(succeededFuture(List.of(sequence)))
        .when(claimSequenceService)
        .getClaimSequencesByIds(List.of(sequence.getId()), requestContext);

    var result = pieceUpdateFlowManager.updatePiecesStatuses(pieceIds,
        PieceBatchStatusCollection.ReceivingStatus.CLAIM_SENT, 7, null, null, requestContext);

    assertTrue(result.succeeded());
    ArgumentCaptor<List<Piece>> claimedCaptor = ArgumentCaptor.forClass(List.class);
    verify(pieceStorageService).updateClaimedPieces(claimedCaptor.capture(), eq(requestContext));
    var claimedById = claimedCaptor.getValue().stream()
        .collect(java.util.stream.Collectors.toMap(Piece::getId, piece -> piece));
    assertEquals(2, claimedById.size());
    assertEquals(1, claimedById.get(firstClaim.getId()).getClaimSequence().getStage());
    assertEquals(10, claimedById.get(firstClaim.getId()).getClaimingInterval());
    assertEquals(2, claimedById.get(secondClaim.getId()).getClaimSequence().getStage());
    assertEquals(20, claimedById.get(secondClaim.getId()).getClaimingInterval());
    assertEquals(Piece.ReceivingStatus.CLAIM_SENT, claimedById.get(secondClaim.getId()).getReceivingStatus());

    ArgumentCaptor<PieceCollection> batchCaptor = ArgumentCaptor.forClass(PieceCollection.class);
    verify(pieceStorageService).updatePiecesBatch(batchCaptor.capture(), eq(requestContext));
    var batchPiece = batchCaptor.getValue().getPieces().getFirst();
    assertEquals(noSequencePiece.getId(), batchPiece.getId());
    assertEquals(7, batchPiece.getClaimingInterval());
    assertNull(batchPiece.getClaimSequence());
  }

  @Test
  void shouldRejectClaimWithoutWritingWhenClaimSequenceIsCompleted() {
    var sequence = claimSequence(ClaimSequence.AfterLastStage.STOP, true);
    var title = new Title().withId(UUID.randomUUID().toString()).withAcqUnitIds(List.of());
    var purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString());
    var sequencePoLine = poLine(purchaseOrder).withClaimSequenceId(sequence.getId());
    var otherPoLine = poLine(purchaseOrder);
    var completedPiece = latePiece(sequencePoLine, title)
        .withClaimSequence(new PieceClaimSequence().withId(sequence.getId()).withStage(2));
    var noSequencePiece = latePiece(otherPoLine, title);
    var pieceIds = List.of(completedPiece.getId(), noSequencePiece.getId());
    givenClaimFlow(pieceIds, List.of(completedPiece, noSequencePiece), title, purchaseOrder,
        List.of(sequencePoLine, otherPoLine));
    doReturn(succeededFuture(List.of(sequence)))
        .when(claimSequenceService)
        .getClaimSequencesByIds(List.of(sequence.getId()), requestContext);

    var result = pieceUpdateFlowManager.updatePiecesStatuses(pieceIds,
        PieceBatchStatusCollection.ReceivingStatus.CLAIM_SENT, 7, null, null, requestContext);

    assertTrue(result.failed());
    var exception = (HttpException) result.cause();
    assertEquals(400, exception.getCode());
    var error = exception.getErrors().getErrors().getFirst();
    assertEquals(ErrorCodes.CLAIM_SEQUENCE_COMPLETED.getCode(), error.getCode());
    assertEquals(completedPiece.getId(), error.getParameters().getFirst().getValue());
    assertEquals(sequence.getId(), error.getParameters().get(1).getValue());
    verify(pieceStorageService, never()).updatePiecesBatch(any(), any());
    verify(pieceStorageService, never()).updateClaimedPieces(any(), any());
  }

  @Test
  void shouldClaimWithRequestIntervalWhenClaimSequenceIsInactiveOrNotFound() {
    var inactiveSequence = claimSequence(ClaimSequence.AfterLastStage.STOP, false);
    var missingSequenceId = UUID.randomUUID().toString();
    var title = new Title().withId(UUID.randomUUID().toString()).withAcqUnitIds(List.of());
    var purchaseOrder = new PurchaseOrder().withId(UUID.randomUUID().toString());
    var inactivePoLine = poLine(purchaseOrder).withClaimSequenceId(inactiveSequence.getId());
    var missingPoLine = poLine(purchaseOrder).withClaimSequenceId(missingSequenceId);
    var inactivePiece = latePiece(inactivePoLine, title);
    var missingPiece = latePiece(missingPoLine, title);
    var pieceIds = List.of(inactivePiece.getId(), missingPiece.getId());
    givenClaimFlow(pieceIds, List.of(inactivePiece, missingPiece), title, purchaseOrder,
        List.of(inactivePoLine, missingPoLine));
    doReturn(succeededFuture(List.of(inactiveSequence)))
        .when(claimSequenceService)
        .getClaimSequencesByIds(any(), eq(requestContext));

    var result = pieceUpdateFlowManager.updatePiecesStatuses(pieceIds,
        PieceBatchStatusCollection.ReceivingStatus.CLAIM_SENT, 7, null, null, requestContext);

    assertTrue(result.succeeded());
    verify(claimSequenceService).getClaimSequencesByIds(any(), eq(requestContext));
    verify(pieceStorageService, never()).updateClaimedPieces(any(), any());
    ArgumentCaptor<PieceCollection> batchCaptor = ArgumentCaptor.forClass(PieceCollection.class);
    verify(pieceStorageService, Mockito.times(2)).updatePiecesBatch(batchCaptor.capture(), eq(requestContext));
    batchCaptor.getAllValues().forEach(collection -> {
      assertEquals(7, collection.getPieces().getFirst().getClaimingInterval());
      assertNull(collection.getPieces().getFirst().getClaimSequence());
    });
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
    doReturn(succeededFuture()).when(pieceStorageService).updateClaimedPieces(any(), eq(requestContext));
    doNothing().when(pieceService).receiptConsistencyPiecePoLine(anyString(), eq(requestContext));
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

  private static ClaimSequence claimSequence(ClaimSequence.AfterLastStage afterLastStage, boolean isActive) {
    return new ClaimSequence()
        .withId(UUID.randomUUID().toString())
        .withIsActive(isActive)
        .withAfterLastStage(afterLastStage)
        .withStages(List.of(
            new ClaimSequenceStage().withStage(1).withInterval(10),
            new ClaimSequenceStage().withStage(2).withInterval(20)));
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
        ClaimSequenceService claimSequenceService) {
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
          claimSequenceService);
    }

    @Bean
    ClaimSequenceService claimSequenceService() {
      return mock(ClaimSequenceService.class);
    }
  }
}
