package org.folio.models.pieces;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.folio.rest.acq.model.ClaimSequence;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.rest.jaxrs.model.PieceCollection;
import org.folio.service.pieces.ClaimSequenceStageResolver.StageResolution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PieceBatchStatusUpdateHolder extends BasePieceFlowHolder {

  @Getter
  private Piece.ReceivingStatus receivingStatus;
  @Getter
  private Integer claimingInterval;
  @Getter
  private String internalNote;
  @Getter
  private String externalNote;
  @Getter
  private List<Piece> pieces;
  private String poLineId;
  // Effective (existing and active) claim sequence of the PO line, null = claim without a sequence
  @Getter
  @Setter
  private ClaimSequence claimSequence;
  // Next claim sequence stage per piece id, only for pieces changing into 'Claim sent'
  @Getter
  private final Map<String, StageResolution> stageResolutions = new HashMap<>();

  public PieceBatchStatusUpdateHolder(Piece.ReceivingStatus receivingStatus, Integer claimingInterval, String internalNote,
                                      String externalNote, List<Piece> pieces, String poLineId) {
    this.receivingStatus = receivingStatus;
    this.claimingInterval = claimingInterval;
    this.internalNote = internalNote;
    this.externalNote = externalNote;
    this.pieces = pieces;
    this.poLineId = poLineId;
  }

  @Override
  public String getOrderLineId() {
    return poLineId;
  }

  @Override
  public String getTitleId() {
    return null;
  }

  public PieceCollection getPieceCollection() {
    return new PieceCollection().withPieces(pieces).withTotalRecords(CollectionUtils.size(pieces));
  }

}
