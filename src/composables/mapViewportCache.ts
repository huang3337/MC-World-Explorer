export function trimViewportVisuals<T>(tiles: Map<string, T>, protectedKeys: ReadonlySet<string>, limit: number): void {
  if (!Number.isSafeInteger(limit) || limit < protectedKeys.size) throw new Error("INVALID_ARGUMENTS");
  for (const key of tiles.keys()) {
    if (tiles.size <= limit) break;
    if (!protectedKeys.has(key)) tiles.delete(key);
  }
}
