package org.folio.models.claiming;

import org.folio.rest.acq.model.Organization;
import org.folio.rest.jaxrs.model.Piece;

/**
 * A piece to be claimed, with the PO line and order values that select its claim sequence stage and Claims export configs.
 *
 * @param vendor            vendor of the piece's order
 * @param piece             the piece as read from storage
 * @param acquisitionMethod acquisition method of the piece's PO line, null if the PO line has none
 * @param claimSequenceId   claim sequence of the piece's PO line, null if the PO line has none
 */
public record ClaimPiece(Organization vendor, Piece piece, String acquisitionMethod, String claimSequenceId) {

  public String pieceId() {
    return piece.getId();
  }
}
