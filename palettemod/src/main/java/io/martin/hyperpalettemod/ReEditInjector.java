package io.martin.hyperpalettemod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Adds the "re-edit" data the Xiaomi Media Editor reads (the "edit" HEIF item plus the micamedit
// XMP marker) to a HEIC photo that has none, in memory. Pure Java, no Android classes.
//
// Layout, as written by the 18 Pro camera (read from its photos):
//  - an item of type "edit" holds a protobuf: field 3 { field 1 = the source image (a whole HEIC),
//    field 2 = one operation: name "filter", version "1.0", field 3 = filter{id, progress,
//    render type, colour parameter string, function}, field 11 = "micamera" };
//  - an XMP item (type "mime", application/rdf+xml) names it with
//    micamedit:version="1" micamedit:offset="-1" micamedit:len="<size of the edit item>".
//
// The 17 Ultra camera cannot give the unfiltered source image, so the source here is the saved
// photo itself and the operation is neutral (the filter at 0 %, every Palette value 0): the editor's
// Palette pad then starts at 0 and adjusts on top of the picture as it is.
//
// The HEIF boxes are edited by hand: two items are added to "iinf", "iloc", and "iref" ("cdsc"
// references to "pitm"), the "meta" box grows, every file offset after it moves by the growth, and
// the new data goes into a new "mdat" box at the end. The result is read back and checked before it
// is returned.
final class ReEditInjector {
    static final int FILTER_STANDARD = 66048;   // the camera's id for "no filter"
    static final String NAMESPACE = "http://ns.xiaomi.com/photos/1.0/camera/reedit";

    // The 17 Ultra editor's own record for Chrome, read from a JPEG it saved: version "2", type "LUT",
    // id 142, strength 100, and the Palette values inside the script (here neutral).
    static final int CAMERA_CHROME = 655608;
    static final int EDITOR_CHROME = 142;
    private static final String NATIVE_SCRIPT_HEAD = "Leica;VignetteStrength=0.35@StyleAdjustEffect;"
            + "Algos=12;SharpenIntensity=0.0000;ContrastIntensity=";
    private static final String NATIVE_SCRIPT_TAIL = ";SaturationIntensity=0.0000;Temperature=0.0000;"
            + "Tint=0.0000;Grain=0.0000;VignetteIntensity=0.0000";

    private static final String RENDER_TYPE = "513";
    private static final String COLOR_PARAM_PREFIX = "@CubeLutEffect;cube_strength=0.0;lut_type=0;@";
    private static final String COLOR_PARAM_NEUTRAL = "@StyleAdjustEffect;Algos=0;SharpenIntensity=0.0;"
            + "ContrastIntensity=0.0;SaturationIntensity=0.0;Temperature=0.0;Tint=0.0;Grain=0.0;"
            + "VignetteIntensity=0.0";

    private static final int FTYP = cc("ftyp");
    private static final int META = cc("meta");
    private static final int MDAT = cc("mdat");
    private static final int PITM = cc("pitm");
    private static final int ILOC = cc("iloc");
    private static final int IINF = cc("iinf");
    private static final int INFE = cc("infe");
    private static final int IREF = cc("iref");
    private static final int CDSC = cc("cdsc");
    private static final int IPRP = cc("iprp");
    private static final int IPCO = cc("ipco");
    private static final int IPMA = cc("ipma");
    private static final int COLR = cc("colr");
    private static final int EXIF = cc("Exif");
    private static final int EDIT = cc("edit");
    private static final int MIME = cc("mime");

    private ReEditInjector() {
    }

    // Extracts the raw "Exif" item bytes from a HEIC (including its 4-byte TIFF header offset),
    // or null if the file has no plain-extent Exif item.
    static byte[] extractExif(byte[] file) {
        try {
            List<Box> top = boxes(file, 0, file.length);
            Box meta = null;
            for (Box box : top) {
                if (box.type == META) {
                    meta = box;
                    break;
                }
            }
            if (meta == null) {
                return null;
            }
            List<Box> kids = boxes(file, meta.start + meta.header + 4, meta.end());
            Box iinf = null;
            Box iloc = null;
            for (Box kid : kids) {
                if (kid.type == IINF) {
                    iinf = kid;
                } else if (kid.type == ILOC) {
                    iloc = kid;
                }
            }
            if (iinf == null || iloc == null) {
                return null;
            }
            int iinfVer = file[iinf.start + iinf.header] & 0xFF;
            int entriesAt = iinf.start + iinf.header + 4 + (iinfVer == 0 ? 2 : 4);
            int exifId = -1;
            for (Box infe : boxes(file, entriesAt, iinf.end())) {
                if (infe.type != INFE) {
                    continue;
                }
                int ver = file[infe.start + infe.header] & 0xFF;
                if (ver < 2) {
                    continue;
                }
                int p = infe.start + infe.header + 4;
                int id = ver == 2 ? (int) u16(file, p) : (int) u32(file, p);
                p += (ver == 2 ? 2 : 4) + 2;
                if ((int) u32(file, p) == EXIF) {
                    exifId = id;
                    break;
                }
            }
            if (exifId < 0) {
                return null;
            }
            Iloc table = parseIloc(file, iloc);
            for (Item item : table.items) {
                if (item.id == exifId && item.cm == 0 && item.extents.size() == 1) {
                    Extent e = item.extents.get(0);
                    return Arrays.copyOfRange(file, (int) e.offset, (int) (e.offset + e.length));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // Extracts the raw "colr" property box from a HEIC's ipco (preferring prof/rICC Display P3
    // ICC profiles over nclx), or null if none is present.
    static byte[] extractColr(byte[] file) {
        try {
            Box meta = null;
            for (Box box : boxes(file, 0, file.length)) {
                if (box.type == META) {
                    meta = box;
                    break;
                }
            }
            if (meta == null) {
                return null;
            }
            Box iprp = null;
            for (Box kid : boxes(file, meta.start + meta.header + 4, meta.end())) {
                if (kid.type == IPRP) {
                    iprp = kid;
                    break;
                }
            }
            if (iprp == null) {
                return null;
            }
            Box ipco = null;
            for (Box kid : boxes(file, iprp.start + iprp.header, iprp.end())) {
                if (kid.type == IPCO) {
                    ipco = kid;
                    break;
                }
            }
            if (ipco == null) {
                return null;
            }
            byte[] fallback = null;
            int prof = cc("prof");
            int rIcc = cc("rICC");
            for (Box prop : boxes(file, ipco.start + ipco.header, ipco.end())) {
                if (prop.type == COLR && prop.size >= prop.header + 4) {
                    int subtype = (int) u32(file, prop.start + prop.header);
                    if (subtype == prof || subtype == rIcc) {
                        return Arrays.copyOfRange(file, prop.start, prop.end());
                    }
                    if (fallback == null) {
                        fallback = Arrays.copyOfRange(file, prop.start, prop.end());
                    }
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Replaces an existing colr box in ipco, or appends colrBox to ipco and links it to primaryId
    // in ipma. Returns the rebuilt iprp box bytes, or null if iprp cannot be safely patched.
    private static byte[] patchIprpColr(byte[] file, Box iprp, int primaryId, byte[] colrBox) {
        try {
            List<Box> iprpKids = boxes(file, iprp.start + iprp.header, iprp.end());
            Box ipco = null;
            Box ipma = null;
            for (Box kid : iprpKids) {
                if (kid.type == IPCO && ipco == null) {
                    ipco = kid;
                } else if (kid.type == IPMA && ipma == null) {
                    ipma = kid;
                }
            }
            if (ipco == null || ipma == null) {
                return null;
            }
            List<Box> props = boxes(file, ipco.start + ipco.header, ipco.end());
            int existingColrIdx = -1;
            for (int i = 0; i < props.size(); i++) {
                if (props.get(i).type == COLR) {
                    existingColrIdx = i;
                    break;
                }
            }

            byte[] ipcoBytes;
            byte[] ipmaBytes = null;
            if (existingColrIdx >= 0) {
                // Replace the existing colr property in-place; ipma indices stay valid.
                Box oldColr = props.get(existingColrIdx);
                ByteArrayOutputStream newIpco = new ByteArrayOutputStream(
                        ipco.size - oldColr.size + colrBox.length);
                newIpco.write(file, ipco.start, oldColr.start - ipco.start);
                newIpco.write(colrBox);
                newIpco.write(file, oldColr.end(), ipco.end() - oldColr.end());
                ipcoBytes = setBoxSize(newIpco.toByteArray(), ipco.header);
            } else {
                // Append colrBox to ipco and associate its 1-based index with primaryId in ipma.
                int newIndex = props.size() + 1;
                int ipmaVer = file[ipma.start + ipma.header] & 0xFF;
                int ipmaFlags = ((file[ipma.start + ipma.header + 1] & 0xFF) << 16)
                        | ((file[ipma.start + ipma.header + 2] & 0xFF) << 8)
                        | (file[ipma.start + ipma.header + 3] & 0xFF);
                boolean largeIdx = (ipmaFlags & 1) != 0;
                if (!largeIdx && newIndex > 127) {
                    return null;
                }
                int idSize = ipmaVer < 1 ? 2 : 4;
                int assocSize = largeIdx ? 2 : 1;
                int p = ipma.start + ipma.header + 4;
                long entryCount = u32(file, p);
                p += 4;
                ByteArrayOutputStream newIpma = new ByteArrayOutputStream(ipma.size + assocSize);
                newIpma.write(file, ipma.start, ipma.header + 8);   // header + ver/flags + count
                boolean patchedPrimary = false;
                for (long i = 0; i < entryCount; i++) {
                    if (p + idSize + 1 > ipma.end()) {
                        return null;
                    }
                    int itemId = idSize == 2 ? (int) u16(file, p) : (int) u32(file, p);
                    int count = file[p + idSize] & 0xFF;
                    int assocsLen = count * assocSize;
                    if (p + idSize + 1 + assocsLen > ipma.end()) {
                        return null;
                    }
                    writeUInt(newIpma, itemId, idSize);
                    if (itemId == primaryId && !patchedPrimary && count < 255) {
                        newIpma.write(count + 1);
                        newIpma.write(file, p + idSize + 1, assocsLen);
                        writeUInt(newIpma, newIndex, assocSize);   // essential=0
                        patchedPrimary = true;
                    } else {
                        newIpma.write(count);
                        newIpma.write(file, p + idSize + 1, assocsLen);
                    }
                    p += idSize + 1 + assocsLen;
                }
                if (!patchedPrimary || p != ipma.end()) {
                    return null;
                }
                ipmaBytes = setBoxSize(newIpma.toByteArray(), ipma.header);

                ByteArrayOutputStream newIpco = new ByteArrayOutputStream(
                        ipco.size + colrBox.length);
                newIpco.write(file, ipco.start, ipco.size);
                newIpco.write(colrBox);
                ipcoBytes = setBoxSize(newIpco.toByteArray(), ipco.header);
            }

            ByteArrayOutputStream newIprp = new ByteArrayOutputStream(
                    iprp.size + (ipcoBytes.length - ipco.size)
                            + (ipmaBytes != null ? ipmaBytes.length - ipma.size : 0));
            newIprp.write(file, iprp.start, iprp.header);
            for (Box kid : iprpKids) {
                if (kid == ipco) {
                    newIprp.write(ipcoBytes);
                } else if (kid == ipma && ipmaBytes != null) {
                    newIprp.write(ipmaBytes);
                } else {
                    newIprp.write(file, kid.start, kid.size);
                }
            }
            return setBoxSize(newIprp.toByteArray(), iprp.header);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Returns a copy of a HEIF Exif item with (1) the EXIF IFD1 JPEG thumbnail replaced in-place
    // with filteredThumbJpeg if it fits (or IFD1 detached if not) and (2) all APP2 "XMTH" segments
    // removed (Xiaomi Gallery's stale unfiltered quick-view JPEG).
    static byte[] stripExifThumbnail(byte[] exif) {
        return stripExifThumbnail(exif, null);
    }

    static byte[] stripExifThumbnail(byte[] exif, byte[] filteredThumbJpeg) {
        if (exif == null || exif.length < 24) {
            return exif;
        }
        byte[] copy = Arrays.copyOf(exif, exif.length);
        int off = (int) u32(copy, 0);
        int tiff = 4 + off;
        if (tiff >= 4 && tiff + 8 <= copy.length) {
            boolean bigEndian = copy[tiff] == 'M' && copy[tiff + 1] == 'M';
            boolean littleEndian = copy[tiff] == 'I' && copy[tiff + 1] == 'I';
            if (bigEndian || littleEndian) {
                int magic = tiffU16(copy, tiff + 2, bigEndian);
                if (magic == 42) {
                    int ifd0 = tiff + tiffU32(copy, tiff + 4, bigEndian);
                    if (ifd0 >= tiff && ifd0 + 2 <= copy.length) {
                        int entries = tiffU16(copy, ifd0, bigEndian);
                        int nextIfdPos = ifd0 + 2 + entries * 12;
                        if (nextIfdPos + 4 <= copy.length) {
                            int ifd1Off = tiffU32(copy, nextIfdPos, bigEndian);
                            boolean replaced = ifd1Off > 0 && filteredThumbJpeg != null
                                    && replaceIfd1Thumb(copy, tiff, tiff + ifd1Off, bigEndian,
                                    filteredThumbJpeg);
                            if (!replaced) {
                                copy[nextIfdPos] = 0;
                                copy[nextIfdPos + 1] = 0;
                                copy[nextIfdPos + 2] = 0;
                                copy[nextIfdPos + 3] = 0;
                            }
                        }
                    }
                }
            }
        }
        // Strip APP2 (0xFFE2) segments whose payload starts with "XMTH"
        if (copy[4] != (byte) 0xFF) {
            return copy;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(copy.length);
        out.write(copy, 0, 4);
        int pos = 4;
        boolean strippedAny = false;
        while (pos + 4 <= copy.length && copy[pos] == (byte) 0xFF) {
            int marker = copy[pos + 1] & 0xFF;
            int segLen = (int) u16(copy, pos + 2);
            int total = 2 + segLen;
            if (segLen < 2 || pos + total > copy.length) {
                return copy;   // unexpected layout, leave segments untouched
            }
            boolean isXmth = marker == 0xE2 && segLen >= 6
                    && copy[pos + 4] == 'X' && copy[pos + 5] == 'M'
                    && copy[pos + 6] == 'T' && copy[pos + 7] == 'H';
            if (isXmth) {
                strippedAny = true;
            } else {
                out.write(copy, pos, total);
            }
            pos += total;
        }
        if (pos < copy.length) {
            out.write(copy, pos, copy.length - pos);
        }
        return strippedAny ? out.toByteArray() : copy;
    }

    private static boolean replaceIfd1Thumb(byte[] d, int tiff, int ifd1, boolean bigEndian,
                                            byte[] thumb) {
        if (ifd1 < tiff || ifd1 + 2 > d.length) {
            return false;
        }
        int n = tiffU16(d, ifd1, bigEndian);
        if (ifd1 + 2 + n * 12 > d.length) {
            return false;
        }
        int jpegOff = -1;
        int jpegLen = -1;
        int lenValPos = -1;
        for (int i = 0; i < n; i++) {
            int p = ifd1 + 2 + i * 12;
            int tag = tiffU16(d, p, bigEndian);
            if (tag == 0x0201) {
                jpegOff = tiffU32(d, p + 8, bigEndian);
            } else if (tag == 0x0202) {
                jpegLen = tiffU32(d, p + 8, bigEndian);
                lenValPos = p + 8;
            }
        }
        if (jpegOff <= 0 || jpegLen <= 0 || lenValPos < 0 || thumb.length > jpegLen) {
            return false;
        }
        int dst = tiff + jpegOff;
        if (dst < tiff || dst + jpegLen > d.length) {
            return false;
        }
        System.arraycopy(thumb, 0, d, dst, thumb.length);
        putTiffU32(d, lenValPos, thumb.length, bigEndian);
        return true;
    }

    private static int tiffU16(byte[] d, int p, boolean bigEndian) {
        return bigEndian ? (int) u16(d, p)
                : ((d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8));
    }

    private static int tiffU32(byte[] d, int p, boolean bigEndian) {
        return bigEndian ? (int) u32(d, p)
                : ((d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
                | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24));
    }

    private static void putTiffU32(byte[] d, int p, int v, boolean bigEndian) {
        if (bigEndian) {
            putU32(d, p, v);
        } else {
            d[p] = (byte) v;
            d[p + 1] = (byte) (v >>> 8);
            d[p + 2] = (byte) (v >>> 16);
            d[p + 3] = (byte) (v >>> 24);
        }
    }

    // Returns true if the HEIC contains an "edit" item in iinf, or if the JPEG's XMP already
    // contains a micamedit marker.
    static boolean hasEdit(byte[] file) {
        if (isJpeg(file)) {
            return hasJpegEdit(file);
        }
        try {
            List<Box> top = boxes(file, 0, file.length);
            Box meta = null;
            for (Box box : top) {
                if (box.type == META) {
                    meta = box;
                    break;
                }
            }
            if (meta == null) {
                return false;
            }
            Box iinf = null;
            for (Box kid : boxes(file, meta.start + meta.header + 4, meta.end())) {
                if (kid.type == IINF) {
                    iinf = kid;
                    break;
                }
            }
            if (iinf == null) {
                return false;
            }
            int iinfVer = file[iinf.start + iinf.header] & 0xFF;
            int entriesAt = iinf.start + iinf.header + 4 + (iinfVer == 0 ? 2 : 4);
            for (Box infe : boxes(file, entriesAt, iinf.end())) {
                if (infe.type != INFE) {
                    continue;
                }
                int ver = file[infe.start + infe.header] & 0xFF;
                if (ver < 2) {
                    continue;
                }
                int p = infe.start + infe.header + 4 + (ver == 2 ? 2 : 4) + 2;
                if ((int) u32(file, p) == EDIT) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // The photo's own camera filter id, read from the camera's private JSON in the Exif item
    // ({"mirror":false,...,"filterId":655608,...}), or -1.
    static int cameraFilterId(byte[] file) {
        byte[] key = "\"filterId\":".getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(file, key, file.length);
        if (at < 0) {
            return -1;
        }
        long value = 0;
        int digits = 0;
        for (int i = at + key.length; i < file.length && digits < 10; i++, digits++) {
            int c = file[i];
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
        }
        return digits == 0 ? -1 : (int) Math.min(value, Integer.MAX_VALUE);
    }

    static boolean isHeic(byte[] file) {
        if (file == null || file.length < 24 || u32(file, 4) != FTYP) {
            return false;
        }
        int size = (int) Math.min(u32(file, 0), file.length);
        for (int p = 8; p + 4 <= size; p += 4) {
            int brand = (int) u32(file, p);
            if (brand == cc("heic") || brand == cc("mif1")) {
                return true;
            }
        }
        return false;
    }

    static boolean isJpeg(byte[] file) {
        return file != null && file.length >= 4
                && (file[0] & 0xFF) == 0xFF && (file[1] & 0xFF) == 0xD8;
    }

    static boolean isSupportedImage(byte[] file) {
        return isHeic(file) || isJpeg(file);
    }

    // Returns the photo with the re-edit data added, or null when it already has an "edit" item.
    // Throws IOException when the file is not laid out as expected; the caller then keeps the
    // original untouched.
    static byte[] inject(byte[] file, int editorFilterId, int progress) throws IOException {
        return inject(file, file, null, editorFilterId, progress, editorFilterId == EDITOR_CHROME,
                null);
    }

    static byte[] inject(byte[] file, int editorFilterId, int progress, boolean nativeStyle)
            throws IOException {
        return inject(file, file, null, editorFilterId, progress, nativeStyle, null);
    }

    static byte[] inject(byte[] file, int editorFilterId, int progress, boolean nativeStyle,
                         String styleAdjust) throws IOException {
        return inject(file, file, null, editorFilterId, progress, nativeStyle, styleAdjust);
    }

    static byte[] inject(byte[] file, byte[] sourceImage, byte[] extraExif, int editorFilterId,
                         int progress, boolean nativeStyle, String styleAdjust)
            throws IOException {
        List<Box> top = boxes(file, 0, file.length);
        Box meta = null;
        for (Box box : top) {
            if (box.type == META && meta == null) {
                meta = box;
            }
        }
        if (meta == null) {
            throw new IOException("no meta box");
        }
        int metaBody = meta.start + meta.header + 4;   // after the full box version and flags
        List<Box> kids = boxes(file, metaBody, meta.end());
        Box pitm = null;
        Box iinf = null;
        Box iloc = null;
        Box iref = null;
        Box iprp = null;
        for (Box kid : kids) {
            if (kid.type == PITM) {
                pitm = kid;
            } else if (kid.type == IINF) {
                iinf = kid;
            } else if (kid.type == ILOC) {
                iloc = kid;
            } else if (kid.type == IREF) {
                iref = kid;
            } else if (kid.type == IPRP) {
                iprp = kid;
            }
        }
        if (pitm == null || iinf == null || iloc == null) {
            throw new IOException("no pitm, iinf or iloc box");
        }
        int pitmVersion = file[pitm.start + pitm.header] & 0xFF;
        int primaryId = pitmVersion == 0
                ? (int) u16(file, pitm.start + pitm.header + 4)
                : (int) u32(file, pitm.start + pitm.header + 4);

        // ---- iinf ----
        int iinfVersion = file[iinf.start + iinf.header] & 0xFF;
        int countAt = iinf.start + iinf.header + 4;
        int countSize = iinfVersion == 0 ? 2 : 4;
        int entriesAt = countAt + countSize;
        List<Box> infes = boxes(file, entriesAt, iinf.end());
        int maxId = 0;
        int xmpId = -1;
        boolean hasExif = false;
        for (Box infe : infes) {
            if (infe.type != INFE) {
                throw new IOException("unexpected box in iinf");
            }
            int version = file[infe.start + infe.header] & 0xFF;
            if (version < 2) {
                continue;
            }
            int p = infe.start + infe.header + 4;
            int id;
            if (version == 2) {
                id = (int) u16(file, p);
                p += 2;
            } else {
                id = (int) u32(file, p);
                p += 4;
            }
            p += 2;   // protection index
            int itemType = (int) u32(file, p);
            p += 4;
            maxId = Math.max(maxId, id);
            if (itemType == EDIT) {
                return null;   // already has re-edit data
            }
            if (itemType == EXIF) {
                hasExif = true;
            }
            if (itemType == MIME && xmpId < 0) {
                p = skipCString(file, p, infe.end());   // item name
                String contentType = cString(file, p, infe.end());
                if (contentType.equals("application/rdf+xml")) {
                    xmpId = id;
                }
            }
        }
        boolean addExifItem = !hasExif && extraExif != null && extraExif.length > 0;
        if (maxId + 3 > 0xFFFF) {
            throw new IOException("item ids too large");
        }
        int editId = maxId + 1;
        int newXmpId = maxId + 2;
        int newExifId = maxId + 3;

        // ---- iloc ----
        Iloc table = parseIloc(file, iloc);
        Item xmpItem = null;
        for (Item item : table.items) {
            if (item.id == xmpId) {
                xmpItem = item;
            }
        }
        if (xmpId >= 0 && (xmpItem == null || xmpItem.cm != 0 || xmpItem.extents.size() != 1)) {
            throw new IOException("existing XMP item is not a plain file extent");
        }

        // ---- the new data ----
        byte[] edit = buildEdit(sourceImage, editorFilterId, progress, nativeStyle, styleAdjust);
        byte[] xmp;
        if (xmpId >= 0) {
            Extent e = xmpItem.extents.get(0);
            byte[] old = Arrays.copyOfRange(file, (int) e.offset, (int) (e.offset + e.length));
            xmp = mergeXmp(old, edit.length);
        } else {
            xmp = newXmp(edit.length);
        }

        // ---- optional colr (Display P3 / ICC profile) copy from sourceImage ----
        byte[] iprpBytes = null;
        int iprpGrowth = 0;
        if (file != sourceImage && iprp != null) {
            byte[] srcColr = extractColr(sourceImage);
            if (srcColr != null) {
                iprpBytes = patchIprpColr(file, iprp, primaryId, srcColr);
                if (iprpBytes != null) {
                    iprpGrowth = iprpBytes.length - iprp.size;
                }
            }
        }

        // ---- sizes first, because every offset after meta moves by the growth ----
        boolean addXmpItem = xmpId < 0;
        byte[] editInfe = infe(editId, "edit", null);
        byte[] xmpInfe = addXmpItem ? infe(newXmpId, "mime", "application/rdf+xml") : new byte[0];
        byte[] exifInfe = addExifItem ? infe(newExifId, "Exif", null) : new byte[0];
        int addedItems = 1 + (addXmpItem ? 1 : 0) + (addExifItem ? 1 : 0);
        int iinfGrowth = editInfe.length + xmpInfe.length + exifInfe.length;
        int perEntry = table.entrySize();
        int ilocGrowth = perEntry * addedItems;

        int irefVersion = iref == null ? 0 : (file[iref.start + iref.header] & 0xFF);
        int irefIdSize = irefVersion == 0 ? 2 : 4;
        byte[] editRef = cdsc(editId, primaryId, irefIdSize);
        byte[] xmpRef = addXmpItem ? cdsc(newXmpId, primaryId, irefIdSize) : new byte[0];
        byte[] exifRef = addExifItem ? cdsc(newExifId, primaryId, irefIdSize) : new byte[0];
        int irefGrowth = (iref == null ? 12 : 0) + editRef.length + xmpRef.length + exifRef.length;

        int growth = iinfGrowth + ilocGrowth + irefGrowth + iprpGrowth;
        long metaEnd = meta.end();

        long newMdatStart = (long) file.length + growth;
        long editOffset = newMdatStart + 8;
        long xmpOffset = editOffset + edit.length;
        long exifOffset = xmpOffset + xmp.length;
        long newFileLength = exifOffset + (addExifItem ? extraExif.length : 0);
        if (newFileLength > Integer.MAX_VALUE - 16) {
            throw new IOException("file too large");
        }

        // ---- iinf ----
        ByteArrayOutputStream newIinf = new ByteArrayOutputStream(iinf.size + iinfGrowth);
        newIinf.write(file, iinf.start, iinf.header + 4);   // box header, version and flags
        writeUInt(newIinf, infes.size() + addedItems, countSize);
        newIinf.write(file, entriesAt, iinf.end() - entriesAt);
        newIinf.write(editInfe);
        newIinf.write(xmpInfe);
        newIinf.write(exifInfe);
        byte[] iinfBytes = setBoxSize(newIinf.toByteArray(), iinf.header);

        // ---- iloc ----
        List<Item> items = new ArrayList<>();
        for (Item item : table.items) {
            Item copy = item.copy();
            if (copy.cm == 0) {
                for (Extent e : copy.extents) {
                    if (e.offset >= metaEnd) {
                        e.offset += growth;
                    }
                }
            }
            if (copy.id == xmpId) {
                Extent e = copy.extents.get(0);
                e.offset = xmpOffset;
                e.length = xmp.length;
            }
            items.add(copy);
        }
        items.add(Item.file(editId, editOffset, edit.length));
        if (addXmpItem) {
            items.add(Item.file(newXmpId, xmpOffset, xmp.length));
        }
        if (addExifItem) {
            items.add(Item.file(newExifId, exifOffset, extraExif.length));
        }
        byte[] ilocBytes = setBoxSize(table.write(file, iloc, items), iloc.header);

        // ---- iref ----
        byte[] irefBytes;
        if (iref != null) {
            ByteArrayOutputStream newIref = new ByteArrayOutputStream(iref.size + irefGrowth);
            newIref.write(file, iref.start, iref.size);
            newIref.write(editRef);
            newIref.write(xmpRef);
            newIref.write(exifRef);
            irefBytes = setBoxSize(newIref.toByteArray(), iref.header);
        } else {
            ByteArrayOutputStream newIref = new ByteArrayOutputStream(irefGrowth);
            writeUInt(newIref, irefGrowth, 4);
            newIref.write(new byte[]{'i', 'r', 'e', 'f', 0, 0, 0, 0});
            newIref.write(editRef);
            newIref.write(xmpRef);
            newIref.write(exifRef);
            irefBytes = newIref.toByteArray();
        }

        // ---- meta ----
        ByteArrayOutputStream newMeta = new ByteArrayOutputStream(meta.size + growth);
        newMeta.write(file, meta.start, meta.header + 4);
        for (Box kid : kids) {
            if (kid == iinf) {
                newMeta.write(iinfBytes);
            } else if (kid == iloc) {
                newMeta.write(ilocBytes);
            } else if (kid == iref) {
                newMeta.write(irefBytes);
            } else if (kid == iprp && iprpBytes != null) {
                newMeta.write(iprpBytes);
            } else {
                newMeta.write(file, kid.start, kid.size);
            }
        }
        if (iref == null) {
            newMeta.write(irefBytes);
        }
        byte[] metaBytes = setBoxSize(newMeta.toByteArray(), meta.header);
        if (metaBytes.length != meta.size + growth) {
            throw new IOException("meta size mismatch " + metaBytes.length + " vs "
                    + (meta.size + growth));
        }

        // ---- the file ----
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) newFileLength);
        out.write(file, 0, meta.start);
        out.write(metaBytes);
        out.write(file, meta.end(), file.length - meta.end());
        long mdatPayload = (long) edit.length + xmp.length + (addExifItem ? extraExif.length : 0);
        writeUInt(out, 8L + mdatPayload, 4);
        out.write(new byte[]{'m', 'd', 'a', 't'});
        out.write(edit);
        out.write(xmp);
        if (addExifItem) {
            out.write(extraExif);
        }
        byte[] result = out.toByteArray();
        if (result.length != newFileLength) {
            throw new IOException("size mismatch");
        }
        verify(file, result, primaryId, editId, edit, xmpId >= 0 ? xmpId : newXmpId, xmp,
                addXmpItem);
        return result;
    }

    // ---- what the editor reads ---------------------------------------------------------------

    // Prefix map from 17U Media Editor (classes2.dex): Leica/film filters carry their own
    // vignette/noise prefix before @StyleAdjustEffect; non-Leica filters use @StyleAdjustEffect
    // directly with no Leica prefix.
    private static String nativePrefixFor(int filterId) {
        return switch (filterId) {
            case 2, 3, 4, 6, 7, 140, 141, 142, 149, 151 -> "Leica;VignetteStrength=0.35";
            case 5 -> "Leica;VignetteStrength=0.35;NoiseStrength=0.4";
            case 101 -> "Leica;VignetteStrength=0.3;NoiseStrength=0.3";
            case 138 -> "Leica;NoiseStrength=0.2";
            default -> "";
        };
    }

    private static byte[] buildEdit(byte[] image, int filterId, int progress, boolean nativeStyle,
                                    String styleAdjust) throws IOException {
        ByteArrayOutputStream filter = new ByteArrayOutputStream();
        putVarint(filter, 1, filterId);
        putVarint(filter, 2, nativeStyle ? (progress > 0 ? progress : 100) : progress);
        putBytes(filter, 3, (nativeStyle ? "LUT" : RENDER_TYPE).getBytes(StandardCharsets.UTF_8));
        String colorParam;
        if (nativeStyle) {
            String prefix = nativePrefixFor(filterId);
            String style = styleAdjust != null ? styleAdjust
                    : "@StyleAdjustEffect;Algos=12;SharpenIntensity=0.0000;ContrastIntensity=0.0000;"
                    + "SaturationIntensity=0.0000;Temperature=0.0000;Tint=0.0000;Grain=0.0000;"
                    + "VignetteIntensity=0.0000";
            colorParam = prefix + style;
        } else {
            colorParam = COLOR_PARAM_PREFIX
                    + (styleAdjust != null ? styleAdjust : COLOR_PARAM_NEUTRAL);
        }
        putBytes(filter, 4, colorParam.getBytes(StandardCharsets.UTF_8));
        putBytes(filter, 5, "APPLY_FILTER".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream op = new ByteArrayOutputStream();
        putBytes(op, 1, "filter".getBytes(StandardCharsets.UTF_8));
        putBytes(op, 2, (nativeStyle ? "2" : "1.0").getBytes(StandardCharsets.UTF_8));
        putBytes(op, 3, filter.toByteArray());
        if (!nativeStyle) {
            putBytes(op, 11, "micamera".getBytes(StandardCharsets.UTF_8));
        }

        ByteArrayOutputStream inner = new ByteArrayOutputStream(image.length + 256);
        putBytes(inner, 1, image);
        putBytes(inner, 2, op.toByteArray());

        ByteArrayOutputStream outer = new ByteArrayOutputStream(inner.size() + 16);
        putBytes(outer, 3, inner.toByteArray());
        return outer.toByteArray();
    }

    private static byte[] newXmp(int editLength) {
        return newXmp(-1, editLength);
    }

    private static byte[] newXmp(int offset, int editLength) {
        return ("<x:xmpmeta\n"
                + "  xmlns:x=\"adobe:ns:meta/\"\n"
                + "  x:xmptk=\"Adobe XMP Core 5.1.2\">\n"
                + "  <rdf:RDF\n"
                + "    xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + descriptionFor(offset, editLength)
                + "  </rdf:RDF>\n"
                + "</x:xmpmeta>\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String descriptionFor(int offset, int editLength) {
        return "    <rdf:Description\n"
                + "      rdf:about=\"\"\n"
                + "      xmlns:micamedit=\"" + NAMESPACE + "\"\n"
                + "      micamedit:version=\"1\"\n"
                + "      micamedit:offset=\"" + offset + "\"\n"
                + "      micamedit:len=\"" + editLength + "\"/>\n";
    }

    private static byte[] mergeXmp(byte[] old, int editLength) throws IOException {
        return mergeXmp(old, -1, editLength);
    }

    private static byte[] mergeXmp(byte[] old, int offset, int editLength) throws IOException {
        String text = new String(old, StandardCharsets.UTF_8);
        if (text.contains("micamedit")) {
            throw new IOException("XMP already mentions micamedit");
        }
        int close = text.lastIndexOf("</rdf:RDF>");
        if (close < 0) {
            throw new IOException("XMP has no rdf:RDF element");
        }
        return (text.substring(0, close) + descriptionFor(offset, editLength)
                + text.substring(close)).getBytes(StandardCharsets.UTF_8);
    }

    // ---- JPEG re-edit injection --------------------------------------------------------------
    // Xiaomi's JPEG re-edit format (from MiPropXmp Wa.a + Za.d + Za.h):
    //  - APP1 XMP carries micamedit:version="1" micamedit:offset="<from EOF>" micamedit:len="<len>"
    //  - Raw ReEditDataBeanMsg protobuf is appended right after JPEG EOI (FF D9), so offset == len.

    private static final byte[] JPEG_EXIF_HDR = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_XMP_HDR =
            "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_XMTH_HDR = "XMTH".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_MPF_HDR = "MPF\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_ICC_HDR = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);

    private static boolean hasJpegEdit(byte[] file) {
        int pos = 2;
        while (pos + 4 <= file.length) {
            while (pos < file.length && (file[pos] & 0xFF) != 0xFF) {
                pos++;
            }
            while (pos < file.length && (file[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= file.length) {
                break;
            }
            int marker = file[pos++] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                continue;
            }
            if (pos + 2 > file.length) {
                break;
            }
            int segLen = ((file[pos] & 0xFF) << 8) | (file[pos + 1] & 0xFF);
            if (segLen < 2 || pos + segLen > file.length) {
                break;
            }
            int payloadStart = pos + 2;
            int payloadLen = segLen - 2;
            if (marker == 0xE1 && startsWith(file, payloadStart, payloadLen, JPEG_XMP_HDR)) {
                String xmp = new String(file, payloadStart + JPEG_XMP_HDR.length,
                        payloadLen - JPEG_XMP_HDR.length, StandardCharsets.UTF_8);
                if (xmp.contains("micamedit") || xmp.contains(NAMESPACE)) {
                    return true;
                }
            }
            pos += segLen;
        }
        return false;
    }

    static byte[] injectJpeg(byte[] outerJpeg, byte[] sourceJpeg, byte[] filteredThumbJpeg,
                             int editorFilterId, int progress, boolean nativeStyle,
                             String styleAdjust) throws IOException {
        if (!isJpeg(outerJpeg) || !isJpeg(sourceJpeg)) {
            throw new IOException("not a JPEG");
        }
        if (hasJpegEdit(sourceJpeg)) {
            return null;
        }

        // Extract Exif, existing XMP, and ICC_PROFILE APP2 segments from sourceJpeg (skip XMTH and
        // sourceJpeg's old MPF, which has offsets for sourceJpeg rather than outerJpeg).
        byte[] srcExif = null;
        byte[] srcXmp = null;
        List<byte[]> srcIcc = new ArrayList<>();
        int pos = 2;
        while (pos + 4 <= sourceJpeg.length) {
            while (pos < sourceJpeg.length && (sourceJpeg[pos] & 0xFF) != 0xFF) {
                pos++;
            }
            while (pos < sourceJpeg.length && (sourceJpeg[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= sourceJpeg.length) {
                break;
            }
            int marker = sourceJpeg[pos++] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                continue;
            }
            if (pos + 2 > sourceJpeg.length) {
                break;
            }
            int segLen = ((sourceJpeg[pos] & 0xFF) << 8) | (sourceJpeg[pos + 1] & 0xFF);
            if (segLen < 2 || pos + segLen > sourceJpeg.length) {
                break;
            }
            int payloadStart = pos + 2;
            int payloadLen = segLen - 2;
            if (marker == 0xE1) {
                if (srcExif == null && startsWith(sourceJpeg, payloadStart, payloadLen, JPEG_EXIF_HDR)) {
                    srcExif = Arrays.copyOfRange(sourceJpeg, payloadStart, payloadStart + payloadLen);
                } else if (srcXmp == null && startsWith(sourceJpeg, payloadStart, payloadLen, JPEG_XMP_HDR)) {
                    srcXmp = Arrays.copyOfRange(sourceJpeg, payloadStart + JPEG_XMP_HDR.length,
                            payloadStart + payloadLen);
                }
            } else if (marker == 0xE2) {
                if (startsWith(sourceJpeg, payloadStart, payloadLen, JPEG_ICC_HDR)) {
                    srcIcc.add(Arrays.copyOfRange(sourceJpeg, payloadStart, payloadStart + payloadLen));
                }
            }
            pos += segLen;
        }

        // If outerJpeg is an Ultra HDR JPEG (e.g. from Bitmap.compress with Gainmap), prefer its
        // XMP so Container:Directory / GainMap Item:Length matches outerJpeg's secondary image.
        byte[] outerHdrXmp = extractUltraHdrXmp(outerJpeg);

        if (srcExif != null && filteredThumbJpeg != null) {
            // Reuse stripExifThumbnail (expects 4-byte BE offset to TIFF header: 6 for "Exif\0\0").
            byte[] wrapped = new byte[4 + srcExif.length];
            wrapped[3] = (byte) JPEG_EXIF_HDR.length;
            System.arraycopy(srcExif, 0, wrapped, 4, srcExif.length);
            byte[] patched = stripExifThumbnail(wrapped, filteredThumbJpeg);
            if (patched != null && patched.length > 4) {
                srcExif = Arrays.copyOfRange(patched, 4, patched.length);
            }
        }

        byte[] edit = buildEdit(sourceJpeg, editorFilterId, progress, nativeStyle, styleAdjust);
        byte[] baseXmp = outerHdrXmp != null ? outerHdrXmp : srcXmp;
        byte[] xmpBody = baseXmp != null ? mergeXmp(baseXmp, edit.length, edit.length)
                : newXmp(edit.length, edit.length);
        if (JPEG_XMP_HDR.length + xmpBody.length + 2 > 0xFFFF) {
            throw new IOException("XMP APP1 exceeds 64 KB JPEG segment limit");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                outerJpeg.length + edit.length + (srcExif != null ? srcExif.length : 0) + 4096);
        out.write(0xFF);
        out.write(0xD8);

        if (srcExif != null && srcExif.length + 2 <= 0xFFFF) {
            writeJpegSegment(out, 0xE1, srcExif);
        }
        byte[] xmpPayload = new byte[JPEG_XMP_HDR.length + xmpBody.length];
        System.arraycopy(JPEG_XMP_HDR, 0, xmpPayload, 0, JPEG_XMP_HDR.length);
        System.arraycopy(xmpBody, 0, xmpPayload, JPEG_XMP_HDR.length, xmpBody.length);
        writeJpegSegment(out, 0xE1, xmpPayload);
        for (byte[] icc : srcIcc) {
            if (icc.length + 2 <= 0xFFFF) {
                writeJpegSegment(out, 0xE2, icc);
            }
        }

        // Copy image body from outerJpeg, skipping its APP0/APP1 and non-MPF APP2 segments so we
        // don't duplicate Exif/XMP/ICC/XMTH. If outerJpeg contains an Ultra HDR MPF segment, keep
        // it and adjust MPEntry[0].size by the net header growth before MPF; everything after MPF
        // (including the secondary GainMap JPEG) is copied verbatim so relative MPF offsets hold.
        int opos = 2;
        boolean sawMpf = false;
        while (opos + 2 <= outerJpeg.length) {
            int segStart = opos;
            while (opos < outerJpeg.length && (outerJpeg[opos] & 0xFF) != 0xFF) {
                opos++;
            }
            while (opos < outerJpeg.length && (outerJpeg[opos] & 0xFF) == 0xFF) {
                opos++;
            }
            if (opos >= outerJpeg.length) {
                break;
            }
            int marker = outerJpeg[opos++] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                out.write(outerJpeg, segStart, outerJpeg.length - segStart);
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                out.write(outerJpeg, segStart, opos - segStart);
                continue;
            }
            if (opos + 2 > outerJpeg.length) {
                throw new IOException("truncated JPEG segment");
            }
            int segLen = ((outerJpeg[opos] & 0xFF) << 8) | (outerJpeg[opos + 1] & 0xFF);
            if (segLen < 2 || opos + segLen > outerJpeg.length) {
                throw new IOException("invalid JPEG segment length");
            }
            int payloadStart = opos + 2;
            int payloadLen = segLen - 2;
            int segEnd = opos + segLen;
            if (sawMpf) {
                out.write(outerJpeg, segStart, segEnd - segStart);
            } else if (marker == 0xE2 && startsWith(outerJpeg, payloadStart, payloadLen, JPEG_MPF_HDR)) {
                byte[] mpfPayload = Arrays.copyOfRange(outerJpeg, payloadStart, payloadStart + payloadLen);
                int primarySizeDelta = out.size() - segStart;
                patchMpfPrimarySize(mpfPayload, primarySizeDelta);
                writeJpegSegment(out, 0xE2, mpfPayload);
                sawMpf = true;
            } else if (marker != 0xE0 && marker != 0xE1 && marker != 0xE2) {
                out.write(outerJpeg, segStart, segEnd - segStart);
            }
            opos = segEnd;
        }

        out.write(edit);
        byte[] result = out.toByteArray();
        if (!hasJpegEdit(result)) {
            throw new IOException("verification failed: micamedit XMP not found in output JPEG");
        }
        return result;
    }

    private static byte[] extractUltraHdrXmp(byte[] jpeg) {
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            while (pos < jpeg.length && (jpeg[pos] & 0xFF) != 0xFF) {
                pos++;
            }
            while (pos < jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= jpeg.length) {
                break;
            }
            int marker = jpeg[pos++] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                continue;
            }
            if (pos + 2 > jpeg.length) {
                break;
            }
            int segLen = ((jpeg[pos] & 0xFF) << 8) | (jpeg[pos + 1] & 0xFF);
            if (segLen < 2 || pos + segLen > jpeg.length) {
                break;
            }
            int payloadStart = pos + 2;
            int payloadLen = segLen - 2;
            if (marker == 0xE1 && startsWith(jpeg, payloadStart, payloadLen, JPEG_XMP_HDR)) {
                byte[] xmp = Arrays.copyOfRange(jpeg, payloadStart + JPEG_XMP_HDR.length,
                        payloadStart + payloadLen);
                String text = new String(xmp, StandardCharsets.UTF_8);
                if (text.contains("GainMap") || text.contains("hdrgm")
                        || text.contains("Container:Directory")) {
                    return xmp;
                }
            }
            pos += segLen;
        }
        return null;
    }

    // Updates MPEntry[0].size (primary image byte length) in an APP2 "MPF\0" payload by delta.
    // Secondary image offsets in MPF are relative to the MPF TIFF header, so they stay valid
    // whenever bytes are inserted only before MPF.
    private static void patchMpfPrimarySize(byte[] mpf, int delta) {
        if (delta == 0 || mpf.length < 16) {
            return;
        }
        int tiff = 4;   // right after "MPF\0"
        boolean bigEndian = mpf[tiff] == 'M' && mpf[tiff + 1] == 'M';
        boolean littleEndian = mpf[tiff] == 'I' && mpf[tiff + 1] == 'I';
        if (!bigEndian && !littleEndian) {
            return;
        }
        if (tiffU16(mpf, tiff + 2, bigEndian) != 42) {
            return;
        }
        int ifd0 = tiff + tiffU32(mpf, tiff + 4, bigEndian);
        if (ifd0 < tiff || ifd0 + 2 > mpf.length) {
            return;
        }
        int count = tiffU16(mpf, ifd0, bigEndian);
        if (ifd0 + 2 + count * 12 > mpf.length) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int p = ifd0 + 2 + i * 12;
            int tag = tiffU16(mpf, p, bigEndian);
            if (tag == 0xB002) {   // MPEntry
                int byteCount = tiffU32(mpf, p + 4, bigEndian);
                int valOff = tiffU32(mpf, p + 8, bigEndian);
                int entry0 = tiff + valOff;
                if (byteCount >= 16 && entry0 >= tiff && entry0 + 16 <= mpf.length) {
                    int oldSize = tiffU32(mpf, entry0 + 4, bigEndian);
                    int newSize = oldSize + delta;
                    if (newSize > 0) {
                        putTiffU32(mpf, entry0 + 4, newSize, bigEndian);
                    }
                }
                return;
            }
        }
    }

    private static void writeJpegSegment(ByteArrayOutputStream out, int marker, byte[] payload) {
        int len = payload.length + 2;
        out.write(0xFF);
        out.write(marker);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(payload, 0, payload.length);
    }

    private static boolean startsWith(byte[] buf, int off, int len, byte[] prefix) {
        if (len < prefix.length || off + prefix.length > buf.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (buf[off + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    // ---- HEIF boxes --------------------------------------------------------------------------

    private static final class Box {
        final int type;
        final int start;
        final int header;
        final int size;

        Box(int type, int start, int header, int size) {
            this.type = type;
            this.start = start;
            this.header = header;
            this.size = size;
        }

        int end() {
            return start + size;
        }
    }

    private static List<Box> boxes(byte[] d, int from, int to) throws IOException {
        List<Box> out = new ArrayList<>();
        int p = from;
        while (p + 8 <= to) {
            long size = u32(d, p);
            int type = (int) u32(d, p + 4);
            int header = 8;
            if (size == 1) {
                if (p + 16 > to) {
                    throw new IOException("short box header at " + p);
                }
                size = u64(d, p + 8);
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) {
                throw new IOException("bad box size at " + p);
            }
            out.add(new Box(type, p, header, (int) size));
            p += (int) size;
        }
        if (p != to) {
            throw new IOException("trailing bytes in box list");
        }
        return out;
    }

    private static byte[] setBoxSize(byte[] box, int header) {
        if (header == 8) {
            putU32(box, 0, box.length);
        } else {
            putU64(box, 8, box.length);
        }
        return box;
    }

    private static byte[] infe(int id, String type, String contentType) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[]{2, 0, 0, 1});   // version 2; flags 1 (hidden), as the camera writes them
        writeUInt(body, id, 2);
        writeUInt(body, 0, 2);                // protection index
        body.write(type.getBytes(StandardCharsets.US_ASCII));
        body.write(0);                        // item name ""
        if (contentType != null) {
            body.write(contentType.getBytes(StandardCharsets.US_ASCII));
            body.write(0);
            body.write(0);                    // content encoding ""
        }
        ByteArrayOutputStream box = new ByteArrayOutputStream();
        writeUInt(box, 8L + body.size(), 4);
        box.write(new byte[]{'i', 'n', 'f', 'e'});
        box.write(body.toByteArray());
        return box.toByteArray();
    }

    private static byte[] cdsc(int fromId, int toId, int idSize) throws IOException {
        ByteArrayOutputStream box = new ByteArrayOutputStream(8 + idSize + 2 + idSize);
        writeUInt(box, 8L + idSize + 2 + idSize, 4);
        box.write(new byte[]{'c', 'd', 's', 'c'});
        writeUInt(box, fromId, idSize);
        writeUInt(box, 1, 2);   // reference count
        writeUInt(box, toId, idSize);
        return box.toByteArray();
    }

    private static final class Extent {
        long index;
        long offset;
        long length;
    }

    private static final class Item {
        int id;
        int cmField;   // the 16 bits that hold the reserved bits and the construction method
        int cm;
        int dataRef;
        List<Extent> extents = new ArrayList<>();

        Item copy() {
            Item c = new Item();
            c.id = id;
            c.cmField = cmField;
            c.cm = cm;
            c.dataRef = dataRef;
            for (Extent e : extents) {
                Extent n = new Extent();
                n.index = e.index;
                n.offset = e.offset;
                n.length = e.length;
                c.extents.add(n);
            }
            return c;
        }

        static Item file(int id, long offset, long length) {
            Item item = new Item();
            item.id = id;
            Extent e = new Extent();
            e.offset = offset;
            e.length = length;
            item.extents.add(e);
            return item;
        }
    }

    private static final class Iloc {
        int version;
        int offsetSize;
        int lengthSize;
        int baseOffsetSize;
        int indexSize;
        List<Item> items = new ArrayList<>();

        int idSize() {
            return version < 2 ? 2 : 4;
        }

        // bytes of one entry with one extent (the entries added here)
        int entrySize() {
            return idSize() + (version >= 1 ? 2 : 0) + 2 + baseOffsetSize + 2
                    + (version >= 1 ? indexSize : 0) + offsetSize + lengthSize;
        }

        byte[] write(byte[] file, Box box, List<Item> all) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(file, box.start, box.header + 4);   // box header, version and flags
            out.write((offsetSize << 4) | lengthSize);
            out.write((baseOffsetSize << 4) | (version >= 1 ? indexSize : 0));
            writeUInt(out, all.size(), version < 2 ? 2 : 4);
            for (Item item : all) {
                writeUInt(out, item.id, idSize());
                if (version >= 1) {
                    writeUInt(out, item.cmField, 2);
                }
                writeUInt(out, item.dataRef, 2);
                writeUInt(out, 0, baseOffsetSize);
                writeUInt(out, item.extents.size(), 2);
                for (Extent e : item.extents) {
                    if (version >= 1 && indexSize > 0) {
                        writeUInt(out, e.index, indexSize);
                    }
                    writeUInt(out, e.offset, offsetSize);
                    writeUInt(out, e.length, lengthSize);
                }
            }
            return out.toByteArray();
        }
    }

    private static Iloc parseIloc(byte[] d, Box box) throws IOException {
        Iloc t = new Iloc();
        int p = box.start + box.header;
        t.version = d[p] & 0xFF;
        if (t.version > 2) {
            throw new IOException("unsupported iloc version " + t.version);
        }
        p += 4;
        t.offsetSize = (d[p] >> 4) & 0xF;
        t.lengthSize = d[p] & 0xF;
        t.baseOffsetSize = (d[p + 1] >> 4) & 0xF;
        t.indexSize = t.version >= 1 ? d[p + 1] & 0xF : 0;
        p += 2;
        if ((t.offsetSize != 4 && t.offsetSize != 8) || (t.lengthSize != 4 && t.lengthSize != 8)
                || t.baseOffsetSize != 0 || (t.indexSize != 0 && t.indexSize != 4
                && t.indexSize != 8)) {
            throw new IOException("unsupported iloc field sizes");
        }
        long count;
        if (t.version < 2) {
            count = u16(d, p);
            p += 2;
        } else {
            count = u32(d, p);
            p += 4;
        }
        for (long i = 0; i < count; i++) {
            Item item = new Item();
            if (t.version < 2) {
                item.id = (int) u16(d, p);
                p += 2;
            } else {
                item.id = (int) u32(d, p);
                p += 4;
            }
            if (t.version >= 1) {
                item.cmField = (int) u16(d, p);
                item.cm = item.cmField & 0xF;
                p += 2;
            }
            item.dataRef = (int) u16(d, p);
            p += 2;
            long extents = u16(d, p);
            p += 2;
            for (long k = 0; k < extents; k++) {
                Extent e = new Extent();
                if (t.version >= 1 && t.indexSize > 0) {
                    e.index = uN(d, p, t.indexSize);
                    p += t.indexSize;
                }
                e.offset = uN(d, p, t.offsetSize);
                p += t.offsetSize;
                e.length = uN(d, p, t.lengthSize);
                p += t.lengthSize;
                item.extents.add(e);
            }
            t.items.add(item);
        }
        if (p != box.end()) {
            throw new IOException("iloc size mismatch");
        }
        return t;
    }

    // ---- check the result ---------------------------------------------------------------------

    private static void verify(byte[] before, byte[] after, int primaryId, int editId, byte[] edit,
                               int xmpId, byte[] xmp, boolean addedXmp) throws IOException {
        List<Box> top = boxes(after, 0, after.length);
        Box meta = null;
        for (Box box : top) {
            if (box.type == META) {
                meta = box;
            }
        }
        if (meta == null) {
            throw new IOException("verify: no meta");
        }
        List<Box> kids = boxes(after, meta.start + meta.header + 4, meta.end());
        Box iloc = null;
        Box iref = null;
        for (Box kid : kids) {
            if (kid.type == ILOC) {
                iloc = kid;
            } else if (kid.type == IREF) {
                iref = kid;
            }
        }
        if (iloc == null || iref == null) {
            throw new IOException("verify: no iloc or iref");
        }
        int irefVer = after[iref.start + iref.header] & 0xFF;
        int refIdSize = irefVer == 0 ? 2 : 4;
        boolean refEdit = false;
        boolean refXmp = !addedXmp;
        for (Box ref : boxes(after, iref.start + iref.header + 4, iref.end())) {
            if (ref.type != CDSC) {
                continue;
            }
            int p = ref.start + ref.header;
            int fromId = refIdSize == 2 ? (int) u16(after, p) : (int) u32(after, p);
            p += refIdSize;
            int count = (int) u16(after, p);
            p += 2;
            for (int i = 0; i < count; i++) {
                int toId = refIdSize == 2 ? (int) u16(after, p) : (int) u32(after, p);
                p += refIdSize;
                if (toId == primaryId) {
                    if (fromId == editId) {
                        refEdit = true;
                    } else if (fromId == xmpId) {
                        refXmp = true;
                    }
                }
            }
        }
        if (!refEdit || !refXmp) {
            throw new IOException("verify: missing iref cdsc (edit " + refEdit + ", xmp "
                    + refXmp + ")");
        }
        Iloc now = parseIloc(after, iloc);
        List<Box> oldTop = boxes(before, 0, before.length);
        Box oldMeta = null;
        for (Box box : oldTop) {
            if (box.type == META) {
                oldMeta = box;
            }
        }
        Iloc was = parseIloc(before, kidsOf(before, oldMeta, ILOC));
        boolean sawEdit = false;
        boolean sawXmp = false;
        for (Item item : now.items) {
            if (item.cm == 0) {
                for (Extent e : item.extents) {
                    if (e.offset < 0 || e.offset + e.length > after.length) {
                        throw new IOException("verify: extent outside the file, item " + item.id);
                    }
                }
            }
            if (item.id == editId) {
                sawEdit = equalAt(after, item, edit);
            } else if (item.id == xmpId) {
                sawXmp = equalAt(after, item, xmp);
            }
        }
        if (!sawEdit || !sawXmp) {
            throw new IOException("verify: new items not readable (edit " + sawEdit + ", xmp "
                    + sawXmp + ")");
        }
        // every original item still reads the same bytes at its new place
        for (Item old : was.items) {
            if (old.id == xmpId || old.cm != 0) {
                continue;
            }
            Item moved = null;
            for (Item item : now.items) {
                if (item.id == old.id) {
                    moved = item;
                }
            }
            if (moved == null || moved.extents.size() != old.extents.size()) {
                throw new IOException("verify: item " + old.id + " lost");
            }
            for (int i = 0; i < old.extents.size(); i++) {
                Extent a = old.extents.get(i);
                Extent b = moved.extents.get(i);
                if (a.length != b.length || !Arrays.equals(
                        Arrays.copyOfRange(before, (int) a.offset, (int) (a.offset + a.length)),
                        Arrays.copyOfRange(after, (int) b.offset, (int) (b.offset + b.length)))) {
                    throw new IOException("verify: item " + old.id + " changed");
                }
            }
        }
    }

    private static Box kidsOf(byte[] d, Box meta, int type) throws IOException {
        for (Box kid : boxes(d, meta.start + meta.header + 4, meta.end())) {
            if (kid.type == type) {
                return kid;
            }
        }
        throw new IOException("verify: no box");
    }

    private static boolean equalAt(byte[] d, Item item, byte[] expected) {
        if (item.extents.size() != 1) {
            return false;
        }
        Extent e = item.extents.get(0);
        if (e.length != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (d[(int) e.offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    // ---- small helpers ------------------------------------------------------------------------

    private static void putVarint(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field << 3));
        writeVarint(out, value);
    }

    private static void putBytes(ByteArrayOutputStream out, int field, byte[] data) {
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeUInt(ByteArrayOutputStream out, long value, int size) throws IOException {
        if (size < 8 && value >= (1L << (8 * size)) || value < 0) {
            throw new IOException("value " + value + " does not fit " + size + " bytes");
        }
        for (int i = size - 1; i >= 0; i--) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    private static int skipCString(byte[] d, int p, int end) throws IOException {
        while (p < end && d[p] != 0) {
            p++;
        }
        if (p >= end) {
            throw new IOException("unterminated string");
        }
        return p + 1;
    }

    private static String cString(byte[] d, int p, int end) {
        int q = p;
        while (q < end && d[q] != 0) {
            q++;
        }
        return new String(d, p, q - p, StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] d, byte[] key, int limit) {
        outer:
        for (int i = 0; i + key.length <= limit; i++) {
            for (int j = 0; j < key.length; j++) {
                if (d[i + j] != key[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int cc(String s) {
        return ((s.charAt(0) & 0xFF) << 24) | ((s.charAt(1) & 0xFF) << 16)
                | ((s.charAt(2) & 0xFF) << 8) | (s.charAt(3) & 0xFF);
    }

    private static long u16(byte[] d, int p) {
        return ((d[p] & 0xFFL) << 8) | (d[p + 1] & 0xFFL);
    }

    private static long u32(byte[] d, int p) {
        return ((d[p] & 0xFFL) << 24) | ((d[p + 1] & 0xFFL) << 16) | ((d[p + 2] & 0xFFL) << 8)
                | (d[p + 3] & 0xFFL);
    }

    private static long u64(byte[] d, int p) {
        return (u32(d, p) << 32) | u32(d, p + 4);
    }

    private static long uN(byte[] d, int p, int size) {
        return size == 4 ? u32(d, p) : size == 8 ? u64(d, p) : 0;
    }

    private static void putU32(byte[] d, int p, long v) {
        d[p] = (byte) (v >>> 24);
        d[p + 1] = (byte) (v >>> 16);
        d[p + 2] = (byte) (v >>> 8);
        d[p + 3] = (byte) v;
    }

    private static void putU64(byte[] d, int p, long v) {
        putU32(d, p, v >>> 32);
        putU32(d, p + 4, v & 0xFFFFFFFFL);
    }
}
