package org.folio.service;

import static one.util.streamex.StreamEx.ofSubLists;
import static org.folio.orders.utils.HelperUtils.collectResultsOnSuccess;
import static org.folio.orders.utils.ResourcePathResolver.CLAIM_SEQUENCES;
import static org.folio.orders.utils.ResourcePathResolver.resourcesPath;
import static org.folio.rest.RestConstants.MAX_IDS_FOR_GET_RQ_15;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.folio.orders.utils.QueryUtils;
import org.folio.rest.core.RestClient;
import org.folio.rest.core.models.RequestContext;
import org.folio.rest.core.models.RequestEntry;
import org.folio.rest.acq.model.ClaimSequence;
import org.folio.rest.acq.model.ClaimSequenceCollection;

import io.vertx.core.Future;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class ClaimSequenceService {

  private static final String ENDPOINT = resourcesPath(CLAIM_SEQUENCES);

  private final RestClient restClient;

  public ClaimSequenceService(RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * Loads the claim sequences with the given ids. Ids without a stored sequence are silently missing from the result.
   */
  public Future<List<ClaimSequence>> getClaimSequencesByIds(Collection<String> ids, RequestContext requestContext) {
    if (ids.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    var futures = ofSubLists(new ArrayList<>(ids), MAX_IDS_FOR_GET_RQ_15)
      .map(QueryUtils::convertIdsToCqlQuery)
      .map(query -> getClaimSequences(query, requestContext))
      .toList();
    return collectResultsOnSuccess(futures)
      .map(lists -> lists.stream().flatMap(Collection::stream).toList())
      .onFailure(t -> log.error("Failed to get claim sequences by ids: {}", ids, t));
  }

  private Future<List<ClaimSequence>> getClaimSequences(String query, RequestContext requestContext) {
    var requestEntry = new RequestEntry(ENDPOINT).withQuery(query).withOffset(0).withLimit(Integer.MAX_VALUE);
    return restClient.get(requestEntry, ClaimSequenceCollection.class, requestContext)
      .map(ClaimSequenceCollection::getClaimSequences);
  }

}
