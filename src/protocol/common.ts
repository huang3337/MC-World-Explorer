export const MAX_RESPONSE_BYTES = 128 * 1024 * 1024;
export const MAX_METADATA_BYTES = 256 * 1024;

export interface SectionView {
  id: number;
  scalar: number;
  offset: number;
  byteLength: number;
  count: number;
}

export interface SectionContract {
  id: number;
  scalar: number;
}

export interface Envelope {
  buffer: ArrayBuffer;
  kind: number;
  sections: Map<number, SectionView>;
  metadata: Record<string, unknown>;
}

const scalarWidths: Readonly<Record<number, number>> = {
  1: 1,
  2: 1,
  3: 2,
  4: 4,
  5: 4,
  6: 4,
};

export function invalidProtocol(): never {
  throw new Error("INVALID_PROTOCOL");
}

function align4(value: number): number {
  return (value + 3) & ~3;
}

export function parseEnvelope(
  value: ArrayBuffer,
  expectedKind: number,
  expectedSections: readonly SectionContract[],
): Envelope {
  if (!(value instanceof ArrayBuffer)
    || value.byteLength < 32
    || value.byteLength > MAX_RESPONSE_BYTES) {
    invalidProtocol();
  }

  const bytes = new Uint8Array(value);
  const view = new DataView(value);
  if (bytes[0] !== 0x4d || bytes[1] !== 0x43 || bytes[2] !== 0x57 || bytes[3] !== 0x45
    || view.getUint16(4, true) !== 1
    || view.getUint16(6, true) !== expectedKind
    || view.getUint32(8, true) !== 32
    || view.getUint32(12, true) !== value.byteLength
    || view.getUint32(24, true) !== 32) {
    invalidProtocol();
  }

  const sectionCount = view.getUint32(28, true);
  const tableEnd = 32 + sectionCount * 16;
  const metadataOffset = view.getUint32(16, true);
  const metadataLength = view.getUint32(20, true);
  if (sectionCount !== expectedSections.length
    || tableEnd > value.byteLength
    || metadataLength > MAX_METADATA_BYTES
    || metadataOffset % 4 !== 0
    || metadataOffset < tableEnd
    || metadataOffset + metadataLength !== value.byteLength) {
    invalidProtocol();
  }

  const sections = new Map<number, SectionView>();
  let expectedOffset = align4(tableEnd);
  for (let index = 0; index < expectedSections.length; index += 1) {
    const at = 32 + index * 16;
    const id = view.getUint16(at, true);
    const scalar = view.getUint16(at + 2, true);
    const offset = view.getUint32(at + 4, true);
    const byteLength = view.getUint32(at + 8, true);
    const count = view.getUint32(at + 12, true);
    const width = scalarWidths[scalar];
    const contract = expectedSections[index];
    if (id !== contract.id
      || scalar !== contract.scalar
      || sections.has(id)
      || width === undefined
      || offset % 4 !== 0
      || offset !== expectedOffset
      || byteLength !== count * width
      || offset + byteLength > metadataOffset) {
      invalidProtocol();
    }
    sections.set(id, { id, scalar, offset, byteLength, count });
    expectedOffset = align4(offset + byteLength);
  }
  if (expectedOffset !== metadataOffset) invalidProtocol();

  let metadata: unknown;
  try {
    metadata = JSON.parse(new TextDecoder("utf-8", { fatal: true })
      .decode(bytes.subarray(metadataOffset)));
  } catch {
    invalidProtocol();
  }
  if (typeof metadata !== "object" || metadata === null || Array.isArray(metadata)) {
    invalidProtocol();
  }
  return {
    buffer: value,
    kind: expectedKind,
    sections,
    metadata: metadata as Record<string, unknown>,
  };
}

export function section(envelope: Envelope, id: number, count?: number): SectionView {
  const value = envelope.sections.get(id);
  if (!value || (count !== undefined && value.count !== count)) invalidProtocol();
  return value;
}
