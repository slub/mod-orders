package org.folio.models.pieces;

import lombok.Getter;
import org.apache.commons.collections4.CollectionUtils;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.rest.jaxrs.model.PieceCollection;
import org.folio.service.pieces.claiming.PieceClaimResolution;

import java.util.List;
import java.util.Map;

public class PieceBatchStatusUpdateHolder extends BasePieceFlowHolder {

  @Getter
  private final Piece.ReceivingStatus receivingStatus;
  @Getter
  private final Integer claimingInterval;
  @Getter
  private final String internalNote;
  @Getter
  private final String externalNote;
  @Getter
  private final List<Piece> pieces;
  private final String poLineId;
  /**
   * Claim resolutions of the pieces changing into 'Claim sent', by piece id; empty for other target statuses
   */
  private Map<String, PieceClaimResolution> claimResolutions = Map.of();

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

  public PieceBatchStatusUpdateHolder withClaimResolutions(Map<String, PieceClaimResolution> claimResolutions) {
    this.claimResolutions = claimResolutions == null ? Map.of() : claimResolutions;
    return this;
  }

  /**
   * @return the claim resolution of the piece, null if the piece does not change into 'Claim sent'
   */
  public PieceClaimResolution getClaimResolution(String pieceId) {
    return pieceId == null ? null : claimResolutions.get(pieceId);
  }

}
