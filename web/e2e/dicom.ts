/**
 * A DICOM Part 10 file, built in the browser suite's own runtime.
 *
 * <p><strong>Why there are three of these.</strong> imaging-service's tests have the full encoder,
 * `tests/api` has a minimal one, and here is a third — which looks like duplication and is really
 * three runtimes. The service's writer runs on the JVM inside the module it tests; the API suite's
 * runs on the JVM and deliberately holds no platform classes, because that independence is what
 * makes it black-box; and this one runs in Node, which can call neither. A file has to be built
 * wherever the test that uploads it lives.
 *
 * <p>Each is also doing a different job, which is why none of them is the "real" one waiting to be
 * shared. The service's writer exercises the parser — implicit VR, both byte orders,
 * undefined-length sequences, a transfer syntax the file switches to halfway through itself. The
 * other two write explicit VR little endian and nothing else, because what they need to prove is
 * that a file carrying an accession number reaches the right request: through the gateway in one
 * case, and through a file input on a screen a radiographer uses in this one.
 *
 * <p>Built rather than committed, for the reason the others also give: a real radiograph is fifteen
 * megabytes of pixels around two hundred bytes of identity, and nobody can review one in a diff.
 * The accession number is minted per run anyway, so the file has to be too.
 */

const EXPLICIT_VR_LE = "1.2.840.10008.1.2.1";
const CR_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.1";

/** `[group, element]`, in the ascending order a dataset must be written in. */
type Tag = readonly [number, number];

const MEDIA_SOP_CLASS_UID: Tag = [0x0002, 0x0002];
const MEDIA_SOP_INSTANCE_UID: Tag = [0x0002, 0x0003];
const TRANSFER_SYNTAX_UID: Tag = [0x0002, 0x0010];
const META_GROUP_LENGTH: Tag = [0x0002, 0x0000];
const SOP_CLASS_UID: Tag = [0x0008, 0x0016];
const SOP_INSTANCE_UID: Tag = [0x0008, 0x0018];
const ACCESSION_NUMBER: Tag = [0x0008, 0x0050];
const MODALITY: Tag = [0x0008, 0x0060];
const STUDY_DESCRIPTION: Tag = [0x0008, 0x1030];
const SERIES_DESCRIPTION: Tag = [0x0008, 0x103e];
const PATIENT_NAME: Tag = [0x0010, 0x0010];
const PATIENT_ID: Tag = [0x0010, 0x0020];
const BODY_PART: Tag = [0x0018, 0x0015];
const STUDY_INSTANCE_UID: Tag = [0x0020, 0x000d];
const SERIES_INSTANCE_UID: Tag = [0x0020, 0x000e];
const SERIES_NUMBER: Tag = [0x0020, 0x0011];
const PIXEL_DATA: Tag = [0x7fe0, 0x0010];

/** Distinct per run, so a re-run does not collide with the study UIDs the last one registered. */
export function runUid(): string {
  return `2.25.${Date.now()}`;
}

/**
 * One instance of one series of one study, carrying `accession`.
 *
 * <p>The patient identifiers in the header are junk on purpose. A DICOM header's identifiers are
 * whatever was typed at a modality console, and the accession number is what the platform matches
 * on — so a fixture whose header agreed with the patient record would not be able to tell the
 * difference between matching on the number and matching on the name.
 */
export function dicomInstance(accession: string, uidRoot: string): Buffer {
  const dataset = Buffer.concat([
    str(SOP_CLASS_UID, "UI", CR_IMAGE_STORAGE),
    str(SOP_INSTANCE_UID, "UI", `${uidRoot}.1.1.1`),
    str(ACCESSION_NUMBER, "SH", accession),
    str(MODALITY, "CS", "CR"),
    str(STUDY_DESCRIPTION, "LO", "Chest PA"),
    str(SERIES_DESCRIPTION, "LO", "PA erect"),
    str(PATIENT_NAME, "PN", "Typed^At^Console"),
    str(PATIENT_ID, "LO", "TYPED-WRONG"),
    str(BODY_PART, "CS", "CHEST"),
    str(STUDY_INSTANCE_UID, "UI", `${uidRoot}.1`),
    str(SERIES_INSTANCE_UID, "UI", `${uidRoot}.1.1`),
    str(SERIES_NUMBER, "IS", "1"),
    // Sixteen bytes of nothing, so the parser has the pixel-data element to stop at. A file with
    // no pixels at all is a structured report, which is a different thing.
    element(PIXEL_DATA, "OW", Buffer.alloc(16)),
  ]);

  // The file meta group: always explicit VR little endian, whatever the dataset is.
  const meta = Buffer.concat([
    str(MEDIA_SOP_CLASS_UID, "UI", CR_IMAGE_STORAGE),
    str(MEDIA_SOP_INSTANCE_UID, "UI", `${uidRoot}.1.1.1`),
    str(TRANSFER_SYNTAX_UID, "UI", EXPLICIT_VR_LE),
  ]);

  return Buffer.concat([
    Buffer.alloc(128),
    Buffer.from("DICM", "ascii"),
    // (0002,0000) counts only what follows it, which is why it is written after the group it
    // measures has been assembled.
    element(META_GROUP_LENGTH, "UL", uint32(meta.length)),
    meta,
    dataset,
  ]);
}

function str(tag: Tag, vr: string, value: string): Buffer {
  let body = Buffer.from(value, "latin1");
  if (body.length % 2 === 1) {
    // Padded to an even length, as the standard requires: with a null for a UID and a space for
    // everything else.
    body = Buffer.concat([body, Buffer.from([vr === "UI" ? 0x00 : 0x20])]);
  }
  return element(tag, vr, body);
}

function element(tag: Tag, vr: string, body: Buffer): Buffer {
  const [group, item] = tag;
  const head = Buffer.alloc(4);
  head.writeUInt16LE(group, 0);
  head.writeUInt16LE(item, 2);
  if (vr === "OW") {
    // OB, OW, OF, SQ, UT and UN carry two reserved bytes and a four-byte length; every other VR
    // carries a two-byte length. Getting this wrong shifts the whole rest of the dataset, which is
    // why the one long-form VR used here is spelled out.
    return Buffer.concat([head, Buffer.from(vr, "ascii"), Buffer.alloc(2), uint32(body.length), body]);
  }
  const length = Buffer.alloc(2);
  length.writeUInt16LE(body.length, 0);
  return Buffer.concat([head, Buffer.from(vr, "ascii"), length, body]);
}

function uint32(value: number): Buffer {
  const out = Buffer.alloc(4);
  out.writeUInt32LE(value, 0);
  return out;
}
