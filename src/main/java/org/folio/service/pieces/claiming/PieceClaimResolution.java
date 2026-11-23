package org.folio.service.pieces.claiming;

import org.folio.rest.acq.model.ClaimSequenceStage;
import org.folio.rest.acq.model.ClaimSequenceStage.DeliveryType;
import org.folio.rest.acq.model.ClaimSequenceStage.TransmissionMethod;

/**
 * How a piece changing into 'Claim sent' is claimed.
 *
 * @param claimSequenceId    claim sequence of the piece's PO line, null for a piece claimed without sequence
 * @param stage              position of the applied stage (1, 2, ...), null without sequence
 * @param sequenceStage      the applied stage, null without sequence
 * @param deliveryType       {@code MANUAL} or {@code INTEGRATION}; {@code INTEGRATION} without sequence (today's behaviour)
 * @param transmissionMethod method of an {@code INTEGRATION} stage, null for {@code MANUAL} and without sequence (= no filter)
 */
public record PieceClaimResolution(String claimSequenceId, Integer stage, ClaimSequenceStage sequenceStage,
                                   DeliveryType deliveryType, TransmissionMethod transmissionMethod) {

  public static final PieceClaimResolution WITHOUT_SEQUENCE =
    new PieceClaimResolution(null, null, null, DeliveryType.INTEGRATION, null);

  public static PieceClaimResolution ofStage(String claimSequenceId, int stage, ClaimSequenceStage sequenceStage) {
    var transmissionMethod = sequenceStage.getDeliveryType() == DeliveryType.INTEGRATION ? sequenceStage.getTransmissionMethod() : null;
    return new PieceClaimResolution(claimSequenceId, stage, sequenceStage, sequenceStage.getDeliveryType(), transmissionMethod);
  }

  public boolean hasSequence() {
    return sequenceStage != null;
  }

  public boolean isManual() {
    return deliveryType == DeliveryType.MANUAL;
  }

  /**
   * @return the transmission method as sent to {@code GET /data-export-spring/effective-configs}, null = no filter
   */
  public String transmissionMethodValue() {
    return transmissionMethod == null ? null : transmissionMethod.value();
  }

  /**
   * @param requestInterval claiming interval of the request
   * @return the interval written to the piece: the stage interval for sequence pieces, otherwise the request interval
   */
  public Integer claimingInterval(Integer requestInterval) {
    return hasSequence() ? sequenceStage.getInterval() : requestInterval;
  }
}
