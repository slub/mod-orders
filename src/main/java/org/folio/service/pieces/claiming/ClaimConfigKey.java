package org.folio.service.pieces.claiming;

/**
 * Key of one {@code GET /data-export-spring/effective-configs} call: pieces sharing it get the same Claims export configs.
 *
 * @param vendorId           vendor (organization id) of the pieces' orders
 * @param acquisitionMethod  acquisition method of the pieces' PO lines, null if they have none
 * @param transmissionMethod transmission method of the pieces' claim sequence stage, null for pieces without sequence
 */
public record ClaimConfigKey(String vendorId, String acquisitionMethod, String transmissionMethod) {
}
