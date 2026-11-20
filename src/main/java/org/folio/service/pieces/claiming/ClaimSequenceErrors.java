package org.folio.service.pieces.claiming;

import static org.folio.rest.core.exceptions.ErrorCodes.CLAIM_SEQUENCE_STAGE_REQUIRES_INTEGRATION;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.folio.HttpStatus;
import org.folio.rest.core.exceptions.HttpException;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Parameter;

/**
 * Builds the 422 {@code claimSequenceStageRequiresIntegration} error: pieces at an {@code INTEGRATION} stage that cannot
 * be sent through a matching Claims integration ({@code POST /orders/pieces/claim} without a matching config,
 * {@code PUT /orders/pieces-batch/status} with {@code CLAIM_SENT}, which never sends anything).
 */
public final class ClaimSequenceErrors {

  public static final String VENDOR_ID_PARAMETER = "vendorId";
  public static final String ACQUISITION_METHOD_PARAMETER = "acquisitionMethod";
  public static final String TRANSMISSION_METHOD_PARAMETER = "transmissionMethod";
  public static final String PIECE_ID_PARAMETER = "pieceId";
  public static final String CLAIM_SEQUENCE_ID_PARAMETER = "claimSequenceId";

  private static final Comparator<String> NULLS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
  private static final Comparator<ClaimConfigKey> GROUP_ORDER = Comparator.comparing(ClaimConfigKey::vendorId, NULLS_FIRST)
    .thenComparing(ClaimConfigKey::acquisitionMethod, NULLS_FIRST)
    .thenComparing(ClaimConfigKey::transmissionMethod, NULLS_FIRST);

  private ClaimSequenceErrors() {
  }

  /**
   * @return a new empty map of keys to piece ids, sorted by vendor id, acquisition method and transmission method
   */
  public static Map<ClaimConfigKey, List<String>> newStageGroups() {
    return new TreeMap<>(GROUP_ORDER);
  }

  public static void addPiece(Map<ClaimConfigKey, List<String>> groups, ClaimConfigKey key, String pieceId) {
    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(pieceId);
  }

  public static List<Error> stageRequiresIntegrationErrors(Map<ClaimConfigKey, List<String>> groups) {
    return groups.entrySet().stream()
      .map(entry -> stageRequiresIntegrationError(entry.getKey(), entry.getValue()))
      .toList();
  }

  public static HttpException unprocessable(Collection<Error> errors) {
    return new HttpException(HttpStatus.HTTP_UNPROCESSABLE_ENTITY.toInt(),
      new Errors().withErrors(List.copyOf(errors)).withTotalRecords(errors.size()));
  }

  private static Error stageRequiresIntegrationError(ClaimConfigKey key, List<String> pieceIds) {
    var parameters = new ArrayList<Parameter>();
    parameters.add(new Parameter().withKey(VENDOR_ID_PARAMETER).withValue(key.vendorId()));
    if (key.acquisitionMethod() != null) {
      parameters.add(new Parameter().withKey(ACQUISITION_METHOD_PARAMETER).withValue(key.acquisitionMethod()));
    }
    parameters.add(new Parameter().withKey(TRANSMISSION_METHOD_PARAMETER).withValue(key.transmissionMethod()));
    pieceIds.stream().distinct().forEach(pieceId -> parameters.add(new Parameter().withKey(PIECE_ID_PARAMETER).withValue(pieceId)));
    var message = String.format(CLAIM_SEQUENCE_STAGE_REQUIRES_INTEGRATION.getDescription(), key.transmissionMethod(),
      key.vendorId(), key.acquisitionMethod() == null ? "none" : key.acquisitionMethod());
    return new Error().withCode(CLAIM_SEQUENCE_STAGE_REQUIRES_INTEGRATION.getCode()).withMessage(message).withParameters(parameters);
  }
}
