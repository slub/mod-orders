package org.folio.service.pieces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.folio.rest.acq.model.ClaimSequence;
import org.folio.rest.acq.model.ClaimSequenceStage;
import org.folio.rest.jaxrs.model.Piece;
import org.folio.rest.jaxrs.model.PieceClaimSequence;
import org.junit.jupiter.api.Test;

public class ClaimSequenceStageResolverTest {

  private static final String SEQUENCE_ID = UUID.randomUUID().toString();
  private static final String OTHER_SEQUENCE_ID = UUID.randomUUID().toString();

  @Test
  void returnsFirstStageWhenPieceHasNoClaimSequence() {
    var sequence = sequence(ClaimSequence.AfterLastStage.STOP);

    var resolution = ClaimSequenceStageResolver.resolve(new Piece(), sequence);

    assertStage(resolution, sequence, 1);
  }

  @Test
  void returnsFirstStageWhenPieceHasSequenceWithoutStage() {
    var sequence = sequence(ClaimSequence.AfterLastStage.STOP);
    var piece = new Piece().withClaimSequence(new PieceClaimSequence().withId(SEQUENCE_ID));

    var resolution = ClaimSequenceStageResolver.resolve(piece, sequence);

    assertStage(resolution, sequence, 1);
  }

  @Test
  void returnsNextStageWhenPieceIsInSameSequence() {
    var sequence = sequence(ClaimSequence.AfterLastStage.STOP);
    var piece = pieceAt(SEQUENCE_ID, 1);

    var resolution = ClaimSequenceStageResolver.resolve(piece, sequence);

    assertStage(resolution, sequence, 2);
  }

  @Test
  void returnsCompletedAfterLastStageWhenSequenceStops() {
    var sequence = sequence(ClaimSequence.AfterLastStage.STOP);
    var piece = pieceAt(SEQUENCE_ID, 3);

    var resolution = ClaimSequenceStageResolver.resolve(piece, sequence);

    assertTrue(resolution.isCompleted());
    assertEquals(SEQUENCE_ID, resolution.claimSequenceId());
    assertNull(resolution.stageNumber());
  }

  @Test
  void returnsLastStageAgainAfterLastStageWhenSequenceRepeatsLast() {
    var sequence = sequence(ClaimSequence.AfterLastStage.REPEAT_LAST);
    var piece = pieceAt(SEQUENCE_ID, 3);

    var resolution = ClaimSequenceStageResolver.resolve(piece, sequence);

    assertStage(resolution, sequence, 3);
  }

  @Test
  void returnsLastStageWhenStoredStageIsBeyondShortenedSequenceAndSequenceRepeatsLast() {
    var sequence = sequence(ClaimSequence.AfterLastStage.REPEAT_LAST);
    var piece = pieceAt(SEQUENCE_ID, 5);

    var resolution = ClaimSequenceStageResolver.resolve(piece, sequence);

    assertStage(resolution, sequence, 3);
  }

  @Test
  void restartsAtFirstStageWhenPieceIsInOtherSequence() {
    var sequence = sequence(ClaimSequence.AfterLastStage.STOP);
    var piece = pieceAt(OTHER_SEQUENCE_ID, 2);

    var resolution = ClaimSequenceStageResolver.resolve(piece, sequence);

    assertStage(resolution, sequence, 1);
  }

  private static void assertStage(ClaimSequenceStageResolver.StageResolution resolution, ClaimSequence sequence, int stageNumber) {
    assertFalse(resolution.isCompleted());
    assertEquals(SEQUENCE_ID, resolution.claimSequenceId());
    assertEquals(stageNumber, resolution.stageNumber());
    assertSame(sequence.getStages().get(stageNumber - 1), resolution.stage());
  }

  private static Piece pieceAt(String claimSequenceId, int stage) {
    return new Piece().withClaimSequence(new PieceClaimSequence().withId(claimSequenceId).withStage(stage));
  }

  private static ClaimSequence sequence(ClaimSequence.AfterLastStage afterLastStage) {
    return new ClaimSequence()
      .withId(SEQUENCE_ID)
      .withAfterLastStage(afterLastStage)
      .withStages(List.of(
        new ClaimSequenceStage().withStage(1).withInterval(10),
        new ClaimSequenceStage().withStage(2).withInterval(20),
        new ClaimSequenceStage().withStage(3).withInterval(30)));
  }

}
