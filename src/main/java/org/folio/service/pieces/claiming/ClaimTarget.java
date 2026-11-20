package org.folio.service.pieces.claiming;

import org.folio.rest.jaxrs.model.Piece;

/**
 * A piece to be set to 'Claim sent', with the claim sequence of its PO line.
 *
 * @param piece           the piece as read from storage
 * @param claimSequenceId {@code claimSequenceId} of the piece's PO line, null if the PO line has none
 */
public record ClaimTarget(Piece piece, String claimSequenceId) {
}
