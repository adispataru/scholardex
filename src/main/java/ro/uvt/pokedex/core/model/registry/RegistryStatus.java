package ro.uvt.pokedex.core.model.registry;

/**
 * Where a name stands in a registry: confirmed (ranked, counted; the CNFIS list carries no status and reads as
 * confirmed), proposed (waiting for the experts), rejected (not an entity to rank) or merged into another as a spelling.
 */
public enum RegistryStatus { CONFIRMED, PROPOSED, REJECTED, MERGED }
