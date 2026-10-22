package org.folio.service.pieces;

import java.util.Objects;

import org.folio.rest.acq.model.ClaimSequence;
import org.folio.rest.acq.model.ClaimSequenceStage;
import org.folio.rest.jaxrs.model.Piece;

/**
 * Works out the claim sequence stage a piece is claimed at next. Pure function, no I/O.
 */
public final class ClaimSequenceStageResolver {

  private ClaimSequenceStageResolver() {
  }

  /**
   * Result of the stage resolution: either the stage number with its stage, or {@code completed}
   * when the piece has been claimed at every stage and the sequence stops afterwards.
   */
  public record StageResolution(String claimSequenceId, Integer stageNumber, ClaimSequenceStage stage) {

    public static StageResolution next(String claimSequenceId, int stageNumber, ClaimSequenceStage stage) {
      return new StageResolution(claimSequenceId, stageNumber, stage);
    }

    public static StageResolution completed(String claimSequenceId) {
      return new StageResolution(claimSequenceId, null, null);
    }

    public boolean isCompleted() {
      return stage == null;
    }
  }

  /**
   * Next stage = stored stage + 1 when the piece is already in this sequence, otherwise stage 1.
   * After the last stage the sequence either stops ({@code STOP}) or repeats its last stage ({@code REPEAT_LAST}).
   * Stages are read by list position; storage returns them in ascending order.
   *
   * @param piece    piece to be claimed
   * @param sequence effective (existing and active) claim sequence of the piece's PO line
   */
  public static StageResolution resolve(Piece piece, ClaimSequence sequence) {
    var stages = sequence.getStages();
    var state = piece.getClaimSequence();
    var isSameSequence = state != null && Objects.equals(sequence.getId(), state.getId()) && state.getStage() != null;
    var next = isSameSequence ? state.getStage() + 1 : 1;
    if (next <= stages.size()) {
      return StageResolution.next(sequence.getId(), next, stages.get(next - 1));
    }
    if (sequence.getAfterLastStage() == ClaimSequence.AfterLastStage.REPEAT_LAST) {
      return StageResolution.next(sequence.getId(), stages.size(), stages.getLast());
    }
    return StageResolution.completed(sequence.getId());
  }

}
